#!/bin/bash

gateway=$1

ip route del default
ip route add default via $gateway

bash