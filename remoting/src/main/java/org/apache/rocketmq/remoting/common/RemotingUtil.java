/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.remoting.common;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.apache.rocketmq.logging.InternalLogger;
import org.apache.rocketmq.logging.InternalLoggerFactory;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.*;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Enumeration;

public class RemotingUtil {
    public static final String OS_NAME = System.getProperty("os.name");

    private static final InternalLogger log = InternalLoggerFactory.getLogger(RemotingHelper.ROCKETMQ_REMOTING);
    private static boolean isLinuxPlatform = false;
    private static boolean isWindowsPlatform = false;

    static {
        if (OS_NAME != null && OS_NAME.toLowerCase().contains("linux")) {
            isLinuxPlatform = true;
        }

        if (OS_NAME != null && OS_NAME.toLowerCase().contains("windows")) {
            isWindowsPlatform = true;
        }
    }

    public static boolean isWindowsPlatform() {
        return isWindowsPlatform;
    }

    public static Selector openSelector() throws IOException {
        Selector result = null;

        if (isLinuxPlatform()) {
            try {
                final Class<?> providerClazz = Class.forName("sun.nio.ch.EPollSelectorProvider");
                if (providerClazz != null) {
                    try {
                        final Method method = providerClazz.getMethod("provider");
                        if (method != null) {
                            final SelectorProvider selectorProvider = (SelectorProvider) method.invoke(null);
                            if (selectorProvider != null) {
                                result = selectorProvider.openSelector();
                            }
                        }
                    } catch (final Exception e) {
                        log.warn("Open ePoll Selector for linux platform exception", e);
                    }
                }
            } catch (final Exception e) {
                // ignore
            }
        }

        if (result == null) {
            result = Selector.open();
        }

        return result;
    }

    public static boolean isLinuxPlatform() {
        return isLinuxPlatform;
    }

    public static String getLocalAddress() {
        try {
            // Traversal Network interface to get the first non-loopback and non-private address
            Enumeration<NetworkInterface> enumeration = NetworkInterface.getNetworkInterfaces();
            ArrayList<String> ipv4Result = new ArrayList<String>();
            ArrayList<String> ipv6Result = new ArrayList<String>();
            while (enumeration.hasMoreElements()) {
                final NetworkInterface networkInterface = enumeration.nextElement();
                if (isBridge(networkInterface)) {
                    continue;
                }

                final Enumeration<InetAddress> en = networkInterface.getInetAddresses();
                while (en.hasMoreElements()) {
                    final InetAddress address = en.nextElement();
                    if (!address.isLoopbackAddress()) {
                        if (address instanceof Inet6Address) {
                            ipv6Result.add(normalizeHostAddress(address));
                        } else {
                            ipv4Result.add(normalizeHostAddress(address));
                        }
                    }
                }
            }

            // prefer ipv4
            if (!ipv4Result.isEmpty()) {
                for (String ip : ipv4Result) {
                    if (ip.startsWith("127.0") || ip.startsWith("192.168")) {
                        continue;
                    }

                    return ip;
                }

                return ipv4Result.get(ipv4Result.size() - 1);
            } else if (!ipv6Result.isEmpty()) {
                return ipv6Result.get(0);
            }
            //If failed to find,fall back to localhost
            final InetAddress localHost = InetAddress.getLocalHost();
            return normalizeHostAddress(localHost);
        } catch (Exception e) {
            log.error("Failed to obtain local address", e);
        }

        return null;
    }

    public static String normalizeHostAddress(final InetAddress localHost) {
        if (localHost instanceof Inet6Address) {
            return "[" + localHost.getHostAddress() + "]";
        } else {
            return localHost.getHostAddress();
        }
    }

    public static SocketAddress string2SocketAddress(final String addr) {
        int split = addr.lastIndexOf(":");
        String host = addr.substring(0, split);
        String port = addr.substring(split + 1);
        InetSocketAddress isa = new InetSocketAddress(host, Integer.parseInt(port));
        return isa;
    }

    public static String socketAddress2String(final SocketAddress addr) {
        StringBuilder sb = new StringBuilder();
        InetSocketAddress inetSocketAddress = (InetSocketAddress) addr;
        sb.append(inetSocketAddress.getAddress().getHostAddress());
        sb.append(":");
        sb.append(inetSocketAddress.getPort());
        return sb.toString();
    }

    private static boolean isBridge(NetworkInterface networkInterface) {
        try {
            if (isLinuxPlatform()) {
                String interfaceName = networkInterface.getName();
                File file = new File("/sys/class/net/" + interfaceName + "/bridge");
                return file.exists();
            }
        } catch (SecurityException e) {
            //Ignore
        }
        return false;
    }

    public static SocketChannel connect(SocketAddress remote) {
        return connect(remote, 1000 * 5);
    }

    public static SocketChannel connect(SocketAddress remote, final int timeoutMillis) {
        SocketChannel sc = null;
        try {
            sc = SocketChannel.open();
            // 连接之前通道是阻塞的,保证通道获取成功
            sc.configureBlocking(true);
            // 调用close后不再阻塞等待而是立即关闭底层连接
            sc.socket().setSoLinger(false, -1);
            // 关闭Nagle算法(包合并批量发送,Nagle算法解决小包问题，提高网络效率。但心跳包slaveoffset的大小是8个字节，如果开启该算法,则心跳包会延迟;所以需要关闭)
            sc.socket().setTcpNoDelay(true);
            // 设置接收缓冲区大小
            sc.socket().setReceiveBufferSize(1024 * 64);
            // 设置发送缓冲区大小
            sc.socket().setSendBufferSize(1024 * 64);

            sc.socket().connect(remote, timeoutMillis);
            // 连接之后通道是非阻塞的
            sc.configureBlocking(false);
            return sc;
        } catch (Exception e) {
            if (sc != null) {
                try {
                    sc.close();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }

        return null;
    }

    public static void closeChannel(Channel channel) {
        final String addrRemote = RemotingHelper.parseChannelRemoteAddr(channel);
        channel.close().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                log.info("closeChannel: close the connection to remote address[{}] result: {}", addrRemote,
                    future.isSuccess());
            }
        });
    }

}
