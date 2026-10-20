# discover-sabnzbd-even-from-within-docker

I want to discover SABnzbd instances on the LAN. It should also work if this "discoverer" runs inside a docker.

A shell / bash script should work

# method on plain host:

## scan port 127.0.0.0:8080
## scan all devices on the lAN on port 8080

# hacks from inside docker

## Check if inside docker

```
root@60a1d0227bc0:/# ping -c3 172.17.0.1
PING 172.17.0.1 (172.17.0.1) 56(84) bytes of data.
64 bytes from 172.17.0.1: icmp_seq=1 ttl=64 time=0.073 ms
64 bytes from 172.17.0.1: icmp_seq=2 ttl=64 time=0.055 ms
64 bytes from 172.17.0.1: icmp_seq=3 ttl=64 time=0.053 ms

--- 172.17.0.1 ping statistics ---
3 packets transmitted, 3 received, 0% packet loss, time 2072ms
rtt min/avg/max/mdev = 0.053/0.060/0.073/0.009 ms
root@60a1d0227bc0:/# 
```

Conclusion: yes, inside docker

## find default gateway of the host

```
root@60a1d0227bc0:/# ping -c3 -t 2 1.1.1.1
PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.
From 192.168.1.254 icmp_seq=1 Time to live exceeded
From 192.168.1.254 icmp_seq=2 Time to live exceeded
From 192.168.1.254 icmp_seq=3 Time to live exceeded

--- 1.1.1.1 ping statistics ---
3 packets transmitted, 0 received, +3 errors, 100% packet loss, time 2002ms

root@60a1d0227bc0:/# 
```

So: default gateway is 192.168.1.254, so LAN is 192.168.1.0/24



## Hack to find host ip address on LAN

```
root@60a1d0227bc0:/# ping -c5 -b 192.168.1.0
PING 192.168.1.0 (192.168.1.0) 56(84) bytes of data.
From 192.168.1.235 icmp_seq=1 Destination Host Unreachable
From 192.168.1.235 icmp_seq=2 Destination Host Unreachable
From 192.168.1.235 icmp_seq=3 Destination Host Unreachable
From 192.168.1.235 icmp_seq=4 Destination Host Unreachable
From 192.168.1.235 icmp_seq=5 Destination Host Unreachable

--- 192.168.1.0 ping statistics ---
5 packets transmitted, 0 received, +5 errors, 100% packet loss, time 4092ms
pipe 3
root@60a1d0227bc0:/# 
```
So: host IPv4 address is 192.168.1.235


## Find SABnzbd instance on port 8080 on host

```
root@60a1d0227bc0:/# curl -v 192.168.1.235:8080 2>&1 | grep -i sabnzbd | wc -l
62

```
Should check both http and https


## Find SABnzbd instance on port 8080 on all hosts on the LAN

... a parallel script that tries on ports 192.168.1.0/24


