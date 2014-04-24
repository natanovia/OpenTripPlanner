package org.opentripplanner.profile;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.joda.time.LocalDate;
import org.onebusaway.gtfs.impl.StopTimeArray;
import org.onebusaway.gtfs.model.AgencyAndId;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.Stop;
import org.opentripplanner.api.param.LatLon;
import org.opentripplanner.api.resource.SimpleIsochrone;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.common.pqueue.BinHeap;
import org.opentripplanner.common.pqueue.OTPPriorityQueue;
import org.opentripplanner.routing.algorithm.GenericAStar;
import org.opentripplanner.routing.algorithm.TraverseVisitor;
import org.opentripplanner.routing.algorithm.strategies.SearchTerminationStrategy;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.edgetype.TripPattern;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.GraphIndex;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.services.SPTService;
import org.opentripplanner.routing.spt.BasicShortestPathTree;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.routing.vertextype.TransitStop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

public class ProfileRouter {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileRouter.class);
    static final int SLACK = 60; // sec
    static final double WALK_SPEED = 1.4; // m/sec FIXME hard coded speed constant
    static final Stop fakeTargetStop = new Stop();
    
    static {
        fakeTargetStop.setId(new AgencyAndId("FAKE", "TARGET"));
    }

    private final Graph graph;
    private final ProfileRequest request;

    public ProfileRouter(Graph graph, ProfileRequest request) {
        this.graph = graph;
        this.request = request;
    }

    Multimap<Stop, Ride> rides = ArrayListMultimap.create();
    Map<TripPattern, StopAtDistance> fromStops, toStops;
    Set<Ride> targetRides = Sets.newHashSet();
    TimeWindow window;

    private boolean pathContainsRoute(Ride ride, Route route) {
        while (ride != null) {
            if (ride.route == route) return true;
            ride = ride.previous;
        }
        return false;
    }

    private boolean pathContainsStop(Ride ride, Stop stop) {
        while (ride != null) {
            if (ride.from == stop) return true;
            if (ride.to   == stop) return true;
            ride = ride.previous;
        }
        return false;
    }
    
    /**
     * Adds a new PatternRide to the PatternRide's destination stop. 
     * If a Ride already exists there on the same route, and with the same previous Ride, 
     * then the new PatternRide is grouped into that existing Ride. Otherwise a new Ride is created.
     * PatternRide may be null, indicating an empty PatternRide with no trips.
     * In this case no Ride is created or updated, and this method returns null.
     * A new set of rides is produced in each round.
     * @return the resulting Ride object, whether it was new or and existing one we merged into. 
     */
    // TODO: rename this
    private Ride addRide (PatternRide pr) {
        /* Catch empty PatternRides with no trips. */
        if (pr == null) return null;
        //LOG.info("new patternride: {}", pr);
        /* Check if the new PatternRide merges into an existing Ride. */
        for (Ride ride : rides.get(pr.getToStop())) {
            if (ride.previous == pr.previous && 
                ride.route    == pr.pattern.route &&
                ride.to       == pr.getToStop()) { // FIXME: how could it not be equal? To-stops should always match in our rides collections
                ride.patternRides.add(pr);
                return ride;
            }
        }
        /* The new ride does not merge into any existing one. */
        Ride ride = new Ride(pr);
        rides.put(pr.getToStop(), ride);
        return ride;
    }

    /**
     * @return true if the given stop has at least one transfer from the given pattern.
     */
    private boolean hasTransfers(Stop stop, TripPattern pattern) {
        for (ProfileTransfer tr : graph.index.transfersForStop.get(stop)) {
            if (tr.tp1 == pattern) return true;
        }
        return false;
    }
    
    /* Maybe don't even try to accumulate stats and weights on the fly, just enumerate options. */
    
    /**
     * Complete a partial PatternRide (with only pattern and beginning stop) which was enqueued in the last round.
     * This is done by scanning through the Pattern, creating rides to all downstream stops that are near the
     * destination or have relevant transfers.
     */
    private void makeRides (PatternRide pr) {
        List<Stop> stops = pr.pattern.getStops();
        Stop targetStop = null;
        if (toStops.containsKey(pr.pattern)) {
            /* This pattern has a stop near the destination. Retrieve it. */
            targetStop = toStops.get(pr.pattern).stop; 
        }
        for (int s = pr.fromIndex + 1; s < stops.size(); ++s) {
            Stop stop = stops.get(s);
            if (targetStop != null && targetStop == stop) {
                Ride ride = addRide(pr.extendToIndex(s, window));
                if (ride != null) targetRides.add(ride);
                // Can we break out here if ride is null? How could later stops have trips?
            } else if (hasTransfers(stop, pr.pattern)) {
                if ( ! pathContainsStop(pr.previous, stop)) { // move this check outside the conditionals?
                    addRide(pr.extendToIndex(s, window)); // safely ignores empty PatternRides
                }
            }
        }
    }
    
    // TimeWindow could actually be resolved and created in the caller, which does have access to the profiledata.
    public ProfileResponse route () {

        /* Set to 2 until we have better pruning. There are a lot of 3-combinations. */
        final int ROUNDS = 3;
        int finalRound = ROUNDS - 1;
        int penultimateRound = ROUNDS - 2;
        findClosestPatterns();
        LOG.info("from stops: {}", fromStops);
        LOG.info("to stops: {}", toStops);
        this.window = new TimeWindow (request.fromTime, request.toTime, graph.index.servicesRunning(request.date));
        /* Our per-round work queue is actually a set, because transferring from a group of patterns
         * can generate the same PatternRide many times. FIXME */
        Set<PatternRide> queue = Sets.newHashSet();
        /* Enqueue one or more PatternRides for each pattern/stop near the origin. */
        for (Entry<TripPattern, StopAtDistance> entry : fromStops.entrySet()) {
            TripPattern pattern = entry.getKey();
            StopAtDistance sd = entry.getValue();
            for (int i = 0; i < pattern.getStops().size(); ++i) {
                if (pattern.getStops().get(i) == sd.stop) {
                    /* Pseudo-transfer from null indicates first leg. */
                    ProfileTransfer xfer = new ProfileTransfer(null, pattern, null, sd.stop, sd.distance);
                    if (request.modes.contains(pattern.mode)) {
                        queue.add(new PatternRide(pattern, i, null, xfer));
                    }
                    /* Do not break in case stop appears more than once in the same pattern. */
                }
            }
        }
        /* One round per ride, as in RAPTOR. */
        for (int round = 0; round < ROUNDS; ++round) {
            LOG.info("ROUND {}", round);
            LOG.info("Queue size at beginning of round: {}", queue.size());
            for (PatternRide pr : queue) {
                LOG.info("  PatternRide: {}", pr);
                makeRides(pr);
            }
            LOG.info("Number of new rides created: {}", rides.size());
            /* Check rides reaching the targets */
//            Set<Stop> uniqueStops = Sets.newHashSet();
//            for (StopAtDistance sad : toStops.values()) {
//                uniqueStops.add(sad.stop);
//            }
//            for (Entry<Pattern, StopAtDistance> entry : toStops.entrySet()) {
//                for (Ride ride : rides.get(entry.getValue().stop)) {
//                    if (ride.patterns.contains(entry.getKey())) ride.dump();
//                }
//            }
            /* Build a new queue for the next round by transferring from patterns in rides */
            if (round != finalRound) {
                queue.clear();
                /* Rides is cleared at the end of each round. */
                for (Ride ride : rides.values()) {
                    LOG.info("RIDE {}", ride);
                    for (ProfileTransfer tr : graph.index.transfersForStop.get(ride.to)) {
                        // LOG.info("  TRANSFER {}", tr);
                        if (round == penultimateRound && !toStops.containsKey(tr.tp2)) continue;
                        if (ride.containsPattern(tr.tp1)) {
                            if (pathContainsRoute(ride, tr.tp2.route)) continue;
                            if (tr.s1 != tr.s2 && pathContainsStop(ride, tr.s2)) continue;
                            // enqueue transfer result state
                            for (int i = 0; i < tr.tp2.getStops().size(); ++i) {
                                if (tr.tp2.getStops().get(i) == tr.s2) {
                                    if (request.modes.contains(tr.tp2.mode)) {
                                        queue.add(new PatternRide(tr.tp2, i, ride, tr));
                                    }
                                    /* Do not break, stop can appear in pattern more than once. */
                                }
                            }
                        }
                    }
                }
                LOG.info("number of new queue states: {}", queue.size());
                rides.clear();
            }
        }
        List<Option> options = Lists.newArrayList();
        for (Ride ride : targetRides) {
            /* We alight from all patterns in a ride at the same stop. */
            int dist = toStops.get(ride.patternRides.get(0).pattern).distance; 
            Option option = new Option (ride, dist, window); // TODO Convert distance to time.
            if ( ! option.hasEmptyRides()) options.add(option); 
        }
        return new ProfileResponse(options, request.orderBy, request.limit);
    }

    // We don't need to retain a routing context or any temp vertices.
    public void findClosestPatterns() {
        LOG.info("Finding nearby routes...");
        // First search forward
        fromStops  = findClosestPatterns(false);
        // Then search backward
        toStops    = findClosestPatterns(true);
        LOG.info("Done.");
    }

    /**
     * Find all patterns that stop close to a given vertex.
     * Return a map from each pattern to the closest stop on that pattern.
     * We want a single stop object rather than an index within the pattern because we want to consider
     * stops that appear more than once in a pattern at every index where they occur.
     * Therefore this is not the right place to convert stop objects to stop indexes.
     */
    public Map<TripPattern, StopAtDistance> findClosestPatterns(boolean back) {
        SimpleIsochrone.MinMap<TripPattern, StopAtDistance> closest = new SimpleIsochrone.MinMap<TripPattern, StopAtDistance>();
        for (StopAtDistance stopDist : findClosestStops(back)) {
            for (TripPattern pattern : graph.index.patternsForStop.get(stopDist.stop)) {
                closest.putMin(pattern, stopDist);
            }
        }
        return closest;
    }

    private List<StopAtDistance> findClosestStops(boolean back) {
        // Make a normal OTP routing request so we can traverse edges and use GenericAStar
        RoutingRequest rr = new RoutingRequest(TraverseMode.WALK);
        rr.setFrom(new GenericLocation(request.from.lat, request.from.lon));
        rr.setTo(new GenericLocation(request.to.lat, request.to.lon));
        rr.setArriveBy(back);
        rr.setRoutingContext(graph);
        // Set batch after context, so both origin and dest vertices will be found.
        rr.setBatch(true);
        // If this is not set, searches are very slow.
        int worstElapsedTime = 60 * 90;
        if (back) worstElapsedTime *= -1;
        rr.setWorstTime(rr.dateTime + worstElapsedTime);
        // Note that the (forward) search is intentionally unlimited so it will reach the destination
        // on-street, even though only transit boarding locations closer than req.streetDist will be used.
        GenericAStar astar = new GenericAStar();
        astar.setNPaths(1);
        final List<StopAtDistance> ret = Lists.newArrayList();
        astar.setTraverseVisitor(new TraverseVisitor() {
            @Override public void visitEdge(Edge edge, State state) { }
            @Override public void visitEnqueue(State state) { }
            /* 1. Accumulate stops into ret as the search runs. */
            @Override public void visitVertex(State state) {
                if (state.getWalkDistance() < request.streetDist) {
                    Vertex vertex = state.getVertex();
                    if (vertex instanceof TransitStop) {
                        TransitStop tstop = (TransitStop) vertex;
                        ret.add(new StopAtDistance(tstop.getStop(), (int) state.getWalkDistance()));
                    }
                }
            }
        });
        /* 2. If this on-street search reaches the destination, record the path. */
        SearchTerminationStrategy terminator = new SearchTerminationStrategy() {
            @Override
            public boolean shouldSearchContinue(Vertex origin, Vertex target, State current, ShortestPathTree spt, RoutingRequest req) {
                if (current.getVertex() != target) return true;
                // current.dumpPath();
                return true;
            }
        };
        ShortestPathTree spt = astar.getShortestPathTree(rr, System.currentTimeMillis() + 5000, terminator);
        rr.rctx.destroy();
        return ret;
    }

    // genericAStar is stalling and I'm tired of trying to figure out why.
    // so I'm re-implementing a non-generic Dijkstra.


}
