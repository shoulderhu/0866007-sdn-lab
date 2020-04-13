package nctu.winlab.bridge;

import com.google.common.collect.Maps;
import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.*;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.packet.*;
import org.onosproject.net.topology.TopologyService;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;


@Component(immediate = true)
public class AppComponent {

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowRuleService flowRuleService;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ApplicationId appId;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    protected Map<DeviceId, Map<MacAddress, PortNumber>> metrics = Maps.newConcurrentMap();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.bridge");
        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        withdrawIntercepts();
        flowRuleService.removeFlowRulesById(appId);
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
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
     * Cancel request for packet in via packet service.
     */
    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    /**
     * Packet processor responsible for forwarding packets along their paths.
     */
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

            short type = ethPkt.getEtherType();

            if (type != Ethernet.TYPE_IPV4 && type != Ethernet.TYPE_ARP) {
                return;
            }

            DeviceId deviceId = pkt.receivedFrom().deviceId();
            metrics.putIfAbsent(deviceId, Maps.newConcurrentMap());

            Map<MacAddress, PortNumber> metric = metrics.get(deviceId);
            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();

            PortNumber inPort = pkt.receivedFrom().port();
            PortNumber outPort = metric.get(dstMac);

            metric.put(srcMac, inPort);

            // Do we know who this is for?
            if (outPort == null) { // If not, flood and bail.
                flood(context);
            } else { // Otherwise forward and be done with it.
                installRule(deviceId, srcMac, dstMac, outPort);
                packetOut(context, outPort);
            }
        }
    }

    // Floods the specified packet is permissible.
    private void flood(PacketContext context) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(),
                context.inPacket().receivedFrom())) {
            packetOut(context, PortNumber.FLOOD);
        } else {
            context.block();
        }
    }

    // Sends a packet out the specified port.
    private void packetOut(PacketContext context, PortNumber portnumber) {
        context.treatmentBuilder().setOutput(portnumber);
        context.send();
    }

    // Install a rule forwarding the packet to the specified port.
    private void installRule(DeviceId deviceId, MacAddress srcMac, MacAddress dstMac, PortNumber outPort) {
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthSrc(srcMac).matchEthDst(dstMac);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .withPriority(10)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(10)
                .add();

        flowObjectiveService.forward(deviceId, forwardingObjective);
    }

    private void installRule2(DeviceId deviceId, MacAddress srcMac, MacAddress dstMac, PortNumber outPort) {
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();
        selectorBuilder.matchEthSrc(srcMac).matchEthDst(dstMac);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort)
                .build();

        FlowRule flowRule = DefaultFlowRule.builder()
                .withSelector(selectorBuilder.build())
                .withTreatment(treatment)
                .forDevice(deviceId)
                .withPriority(10)
                .makeTemporary(10)
                .fromApp(appId)
                .build();

        flowRuleService.applyFlowRules(flowRule);
    }
}
