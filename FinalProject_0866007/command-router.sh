#!/bin/bash

host=$1
asn1=$2
asn2=$3
asn3=$4
ip1=$5
ip2=$6
ip3=$7
net=$8

if [[ -n "$9" ]]
then
    ip addr add 172.21.0.100/16 dev eth0
    ip addr add 172.22.0.100/16 dev eth0
fi

echo "hostname ${host}zebra" >> /etc/quagga/zebra.conf
echo "password sdnip" >> /etc/quagga/zebra.conf
echo "log stdout" >> /etc/quagga/zebra.conf

echo "! BGP configuration for $host" >> /etc/quagga/bgpd.conf
echo "!" >> /etc/quagga/bgpd.conf
echo "hostname ${host}bgp" >> /etc/quagga/bgpd.conf
echo "password sdnip" >> /etc/quagga/bgpd.conf
echo "!" >> /etc/quagga/bgpd.conf
echo "router bgp $asn1" >> /etc/quagga/bgpd.conf
echo "  bgp router-id $ip1" >> /etc/quagga/bgpd.conf
echo "  timers bgp 3 9" >> /etc/quagga/bgpd.conf
echo "  !" >> /etc/quagga/bgpd.conf
echo "  neighbor $ip2 remote-as $asn2" >> /etc/quagga/bgpd.conf
echo "  neighbor $ip2 ebgp-multihop" >> /etc/quagga/bgpd.conf
echo "  neighbor $ip2 timers connect 5" >> /etc/quagga/bgpd.conf
echo "  neighbor $ip2 advertisement-interval 5" >> /etc/quagga/bgpd.conf

if [[ -n "$9" ]]
then
    echo "  !" >> /etc/quagga/bgpd.conf
    echo "  neighbor $ip3 remote-as $asn3" >> /etc/quagga/bgpd.conf
    echo "  neighbor $ip3 ebgp-multihop" >> /etc/quagga/bgpd.conf
    echo "  neighbor $ip3 timers connect 5" >> /etc/quagga/bgpd.conf
    echo "  neighbor $ip3 advertisement-interval 5" >> /etc/quagga/bgpd.conf
    echo "  !" >> /etc/quagga/bgpd.conf
    echo "  neighbor $net remote-as $asn1" >> /etc/quagga/bgpd.conf
    echo "  neighbor $net port 2000" >> /etc/quagga/bgpd.conf
    echo "  neighbor $net timers connect 5" >> /etc/quagga/bgpd.conf
else
    echo "  network $net" >> /etc/quagga/bgpd.conf
    echo "!" >> /etc/quagga/bgpd.conf
    echo "log stdout" >> /etc/quagga/bgpd.conf
fi

/etc/init.d/quagga restart
bash