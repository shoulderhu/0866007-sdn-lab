#!/bin/bash

set -e
set -x

docker-compose up -d --build

### OVS1
docker exec OVS1 ifconfig eth1 172.23.0.5 netmask 255.255.0.0

docker exec OVS1 ovs-vsctl add-br ovs1br
docker exec OVS1 ovs-vsctl set-controller ovs1br tcp:172.18.0.2:6653

docker exec OVS1 ovs-vsctl add-port ovs1br eth0
docker exec OVS1 ovs-vsctl add-port ovs1br eth1
docker exec OVS1 ovs-vsctl add-port ovs1br eth2
# docker exec OVS1 ovs-vsctl show

### OVS2
docker exec OVS2 ifconfig eth0 172.23.0.6 netmask 255.255.0.0

docker exec OVS2 ovs-vsctl add-br ovs2br
docker exec OVS2 ovs-vsctl set-controller ovs2br tcp:172.18.0.2:6653

docker exec OVS2 ovs-vsctl add-port ovs2br eth0
docker exec OVS2 ovs-vsctl add-port ovs2br eth1
# docker exec OVS2 ovs-vsctl show

#curl -u onos:rocks -X POST \
#     --header 'Content-Type: application/json' \
#     --header 'Accept: application/json' \
#     -d @/home/user/0866007-sdn-lab/FinalProject_0866007/network-cfg.json \
#     'http://172.18.0.2:8181/onos/v1/network/configuration'
#
#curl -X DELETE \
#     --header 'Accept: application/json' \
#     'http://172.18.0.2:8181/onos/v1/network/configuration'

#ssh -p 8101 -o StrictHostKeyChecking=no karaf@172.18.0.2
#app activate org.onosproject.openflow
#app activate org.onosproject.proxyarp
#app activate org.onosproject.sdnip

# docker-compose down