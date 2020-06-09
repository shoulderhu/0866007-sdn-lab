package nctu.winlab.unicastdhcp;

import org.onlab.packet.*;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.*;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.host.HostService;
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

import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected PathService pathService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY)
    protected FlowObjectiveService flowObjectiveService;

    private ApplicationId appId;

    private DhcpConfigListener cfgListener = new DhcpConfigListener();
    private ConfigFactory cfgFactory = new ConfigFactory<ApplicationId, DhcpConfig>(
            APP_SUBJECT_FACTORY, DhcpConfig.class, "UnicastDhcpConfig") {
        @Override
        public DhcpConfig createConfig() {
            return new DhcpConfig();
        }
    };
    private DhcpConfig config;

    private ReactivePacketProcessor processor = new ReactivePacketProcessor();

    MacAddress disDstMac;
    Ip4Prefix disSrcIp, disDstIp;

    DeviceId dhcpDeviceId;
    PortNumber dhcpPort;

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.winlab.unicastdhcp");

        cfgService.addListener(cfgListener);
        cfgService.registerConfigFactory(cfgFactory);

        packetService.addProcessor(processor, PacketProcessor.director(2));
        requestIntercepts();

        disDstMac = MacAddress.valueOf("FF:FF:FF:FF:FF:FF");
        disSrcIp = Ip4Prefix.valueOf("0.0.0.0/32");
        disDstIp = Ip4Prefix.valueOf("255.255.255.255/32");

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.removeListener(cfgListener);
        cfgService.unregisterConfigFactory(cfgFactory);

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

    private void requestIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private void withdrawIntercepts() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
    }

    private Path pickForwardPathIfPossible(Set<Path> paths, PortNumber notToPort) {
        for (Path path : paths) {
            if (!path.src().port().equals(notToPort)) {
                return path;
            }
        }

        return null;
    }

    private void installRule(DeviceId deviceId, MacAddress srcMac, MacAddress dstMac,
                             Ip4Prefix srcIp, Ip4Prefix dstIp, PortNumber number) {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();

        if (srcMac == null) {
            selector.matchEthType(Ethernet.TYPE_IPV4)
                    .matchEthDst(dstMac)
                    .matchIPSrc(srcIp)
                    .matchIPDst(dstIp);
        } else {
            selector.matchEthType(Ethernet.TYPE_IPV4)
                    .matchEthSrc(srcMac)
                    .matchEthDst(dstMac);
        }

        TrafficTreatment treatment = DefaultTrafficTreatment.builder().setOutput(number).build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                .withSelector(selector.build())
                .withTreatment(treatment)
                .withPriority(10)
                .withFlag(ForwardingObjective.Flag.VERSATILE)
                .fromApp(appId)
                .makeTemporary(10)
                .add();

        flowObjectiveService.forward(deviceId, forwardingObjective);
    }

    private void packetOut(PacketContext context, PortNumber number) {
        context.treatmentBuilder().setOutput(number);
        context.send();
    }

    private class DhcpConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                    event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED) &&
                    event.configClass().equals(DhcpConfig.class)) {

                config = cfgService.getConfig(appId, DhcpConfig.class);
                if (config != null) {
                    log.info("DHCP server is at {}", config.location());

                    String location[] = config.location().split("/");
                    dhcpDeviceId = DeviceId.deviceId(location[0]);
                    dhcpPort = PortNumber.portNumber(location[1]);
                }
            }
        }
    }

    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            if (ethPkt == null) {
                return;
            }

            MacAddress srcMac = ethPkt.getSourceMAC();
            MacAddress dstMac = ethPkt.getDestinationMAC();

            if (ethPkt.getEtherType() != Ethernet.TYPE_IPV4) {
                return;
            }

            IPv4 payload = (IPv4) ethPkt.getPayload();
            Ip4Prefix srcIp = Ip4Prefix.valueOf(payload.getSourceAddress(), Ip4Prefix.MAX_MASK_LENGTH);
            Ip4Prefix dstIp = Ip4Prefix.valueOf(payload.getDestinationAddress(), Ip4Prefix.MAX_MASK_LENGTH);

            // DHCP DISCOVER, DHCP REQUEST
            if (dstMac.equals(disDstMac) && srcIp.equals(disSrcIp) && dstIp.equals(disDstIp)) {

                if (pkt.receivedFrom().deviceId().equals(dhcpDeviceId)) {
                    installRule(dhcpDeviceId, srcMac, dstMac, srcIp, dstIp, dhcpPort);
                    return;
                }

                Set<Path> paths = pathService.getPaths(pkt.receivedFrom().deviceId(), dhcpDeviceId);
                if (paths.isEmpty()) {
                    log.info("paths.isEmpty()");
                    return;
                }

                Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port());
                if (path == null) {
                    log.info("path == null");
                    return;
                }

                // log.info("path: {}", path);
                List<Link> links = path.links();

                for (int i = links.size() - 1; i >= 0; --i) {
                    // log.info("link: {}", links.get(i));

                    if (i == links.size() - 1) {
                        DeviceId id = links.get(i).dst().deviceId();
                        installRule(id, srcMac, dstMac, srcIp, dstIp, dhcpPort);
                    }

                    DeviceId id = links.get(i).src().deviceId();
                    PortNumber port = links.get(i).src().port();
                    installRule(id, srcMac, dstMac, srcIp, dstIp, port);
                }

                return;
            }

            // DHCP OFFER, DHCP ACK
            if (pkt.receivedFrom().deviceId().equals(dhcpDeviceId) &&
                pkt.receivedFrom().port().equals(dhcpPort)) {

                Host dst = hostService.getHost(HostId.hostId(dstMac));
                if (dst == null) {
                    log.info("dst == null");
                    return;
                }

                DeviceId dstDeviceId = dst.location().deviceId();
                PortNumber dstPort = dst.location().port();

                if (pkt.receivedFrom().deviceId().equals(dstDeviceId)) {
                    installRule(dstDeviceId, srcMac, dstMac, null, null, dstPort);
                    return;
                }

                Set<Path> paths = pathService.getPaths(pkt.receivedFrom().deviceId(), dstDeviceId);
                if (paths.isEmpty()) {
                    log.info("paths.isEmpty()");
                    return;
                }

                Path path = pickForwardPathIfPossible(paths, pkt.receivedFrom().port());
                if (path == null) {
                    log.info("path == null");
                    return;
                }

                // log.info("path: {}", path);
                List<Link> links = path.links();

                for (int i = links.size() - 1; i >= 0; --i) {
                    // log.info("link: {}", links.get(i));

                    if (i == links.size() - 1) {
                        DeviceId id = links.get(i).dst().deviceId();
                        installRule(id, srcMac, dstMac, null, null, dstPort);
                    }

                    DeviceId id = links.get(i).src().deviceId();
                    PortNumber port = links.get(i).src().port();
                    installRule(id, srcMac, dstMac, null, null, port);
                }
            }
        }
    }
}
