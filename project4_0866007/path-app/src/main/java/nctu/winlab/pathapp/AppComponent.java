package nctu.winlab.pathapp;

import com.google.common.collect.ImmutableSet;
import org.onlab.packet.*;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Host;
import org.onosproject.net.Link;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
import org.onosproject.net.link.LinkService;
import org.onosproject.net.packet.*;
import org.onosproject.net.topology.*;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static org.onlab.util.Tools.get;

@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected LinkService linkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    private ApplicationId appId;
    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.pathapp");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        withdrawIntercepts();
        packetService.removeProcessor(processor);
        processor = null;

        log.info("Stopped");
    }

    @Modified
    public void modified(ComponentContext context) {
        requestIntercepts();
        log.info("Reconfigured");
    }

    /**
     * Request packet in via packet service.
     */
    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
     * Cancel request for packet in via packet service.
     */
    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            // Stop processing if the packet has been handled,
            // since we can't do any more to it.
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt == null) {
                return;
            }

            DeviceId deviceId = pkt.receivedFrom().deviceId();
            log.info("Packet-in from device {}", deviceId);

            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();

            IPv4 payload = (IPv4) ethPkt.getPayload();
            Ip4Prefix srcIP = Ip4Prefix.valueOf(payload.getSourceAddress(), Ip4Prefix.MAX_MASK_LENGTH);
            Ip4Prefix dstIP = Ip4Prefix.valueOf(payload.getDestinationAddress(), Ip4Prefix.MAX_MASK_LENGTH);

            Topology topology = topologyService.currentTopology();
            TopologyGraph graph = topologyService.getGraph(topology);
            TopologyVertex srcVtx = null, dstVtx = null;

            for (TopologyVertex vertex: graph.getVertexes()) {
                for (Host host: hostService.getConnectedHosts(vertex.deviceId())) {
                    if (host.mac().equals(srcMac)) {
                        srcVtx = vertex;
                    }
                    if (host.mac().equals(dstMac)) {
                        dstVtx = vertex;
                    }
                }
            }

            if (srcVtx == null || dstVtx == null) {
                log.info("srcVertex == null || dstVertex == null");
                return;
            }

            ArrayList<TopologyVertex> path = bfs(graph, srcVtx, dstVtx);
            log.info("Start to install path from {} to {}", srcMac, dstMac);

            /*
            for (TopologyVertex vertex: path) {
                log.info("path: {}", vertex.deviceId());
            }
             */

            for (int i = 0; i < path.size(); ++i) {
                if (i == 0) {
                    DeviceId id = path.get(i).deviceId();
                    Host host = hostService.getHostsByMac(dstMac).iterator().next();
                    PortNumber port = host.location().port();
                    installRule(id, srcIP, dstIP, port);
                    continue;
                }

                for (Link link: linkService.getDeviceEgressLinks(path.get(i).deviceId())) {
                    DeviceId dstId = path.get(i - 1).deviceId();
                    if (link.dst().deviceId().equals(dstId)) {
                        DeviceId id = link.src().deviceId();
                        PortNumber port = link.src().port();
                        installRule(id, srcIP, dstIP, port);
                        break;
                    }
                }
            }
        }
    }

    private ArrayList<TopologyVertex> bfs(TopologyGraph graph, TopologyVertex srcVtx, TopologyVertex dstVtx) {
        ArrayList<TopologyVertex> path = new ArrayList<>();
        ArrayDeque<TopologyVertex> queue = new ArrayDeque<>();
        ArrayDeque<TopologyVertex> isVisit = new ArrayDeque<>();
        HashMap<TopologyVertex, TopologyVertex> parent = new HashMap<>();
        boolean isDone = false;

        if (srcVtx.equals(dstVtx)) {
            path.add(srcVtx);
            return path;
        }

        queue.offer(srcVtx);
        isVisit.offer(srcVtx);
        parent.put(srcVtx, srcVtx);

        while (!queue.isEmpty()) {
            TopologyVertex src = queue.poll();

            for (TopologyEdge edge: graph.getEdgesFrom(src)) {
                TopologyVertex dst = edge.dst();

                if (!isVisit.contains(dst)) {
                    queue.offer(dst);
                    isVisit.offer(dst);
                    parent.put(dst, src);

                    if (dst.equals(dstVtx)) {
                        isDone = true;
                        break;
                    }
                }
            }

            if (isDone) {
                TopologyVertex vtx = dstVtx;
                path.add(dstVtx);

                while (!vtx.equals(srcVtx)) {
                    vtx = parent.get(vtx);
                    path.add(vtx);
                }

                break;
            }
        }

        return path;
    }

    // Install a rule forwarding the packet to the specified port.
    private void installRule(DeviceId deviceId, IpPrefix srcIP, IpPrefix dstIP, PortNumber outPort) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPSrc(srcIP)
                .matchIPDst(dstIP);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selector.build())
                .withTreatment(treatment)
                .withPriority(10)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(30)
                .add();

        flowObjectiveService.forward(deviceId, forwardingObjective);
        log.info("Install flow rule on {}", deviceId);
    }
}
