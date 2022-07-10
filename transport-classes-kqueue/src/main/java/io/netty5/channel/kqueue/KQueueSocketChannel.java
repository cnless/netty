/*
 * Copyright 2016 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty5.channel.kqueue;

import io.netty5.buffer.api.Buffer;
import io.netty5.channel.ChannelOutboundBuffer;
import io.netty5.channel.EventLoop;
import io.netty5.channel.socket.SocketChannel;
import io.netty5.channel.unix.IovArray;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.GlobalEventExecutor;
import io.netty5.util.internal.UnstableApi;

import java.net.InetSocketAddress;
import java.net.ProtocolFamily;
import java.net.SocketAddress;
import java.util.concurrent.Executor;

@UnstableApi
public final class KQueueSocketChannel
        extends AbstractKQueueStreamChannel<KQueueServerSocketChannel, SocketAddress, SocketAddress>
        implements SocketChannel {
    private final KQueueSocketChannelConfig config;

    public KQueueSocketChannel(EventLoop eventLoop) {
        this(eventLoop, null);
    }

    public KQueueSocketChannel(EventLoop eventLoop, ProtocolFamily protocol) {
        super(null, eventLoop, BsdSocket.newSocketStream(protocol), false);
        config = new KQueueSocketChannelConfig(this);
    }

    public KQueueSocketChannel(EventLoop eventLoop, int fd) {
        super(eventLoop, new BsdSocket(fd));
        config = new KQueueSocketChannelConfig(this);
    }

    KQueueSocketChannel(KQueueServerSocketChannel parent, EventLoop eventLoop,
                        BsdSocket fd, InetSocketAddress remoteAddress) {
        super(parent, eventLoop, fd, remoteAddress);
        config = new KQueueSocketChannelConfig(this);
    }

    @Override
    public KQueueSocketChannelConfig config() {
        return config;
    }

    @Override
    protected boolean doConnect0(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (config.isTcpFastOpenConnect()) {
            ChannelOutboundBuffer outbound = outboundBuffer();
            outbound.addFlush();
            Object curr;
            if ((curr = outbound.current()) instanceof Buffer) {
                Buffer initialData = (Buffer) curr;
                // Don't bother with TCP FastOpen if we don't have any initial data to send anyway.
                if (initialData.readableBytes() > 0) {
                    IovArray iov = new IovArray();
                    try {
                        initialData.forEachReadable(0, iov);
                        int bytesSent = socket.connectx(
                                (InetSocketAddress) localAddress, (InetSocketAddress) remoteAddress, iov, true);
                        writeFilter(true);
                        outbound.removeBytes(Math.abs(bytesSent));
                        // The `connectx` method returns a negative number if connection is in-progress.
                        // So we should return `true` to indicate that connection was established, if it's positive.
                        return bytesSent > 0;
                    } finally {
                        iov.release();
                    }
                }
            }
        }
        return super.doConnect0(remoteAddress, localAddress);
    }

    @Override
    protected Future<Executor> prepareToClose() {
        try {
            // Check isOpen() first as otherwise it will throw a RuntimeException
            // when call getSoLinger() as the fd is not valid anymore.
            if (isOpen() && config().getSoLinger() > 0) {
                // We need to cancel this key of the channel so we may not end up in a eventloop spin
                // because we try to read or write until the actual close happens which may be later due
                // SO_LINGER handling.
                // See https://github.com/netty/netty/issues/4449
                return executor().deregisterForIo(this).map(v -> GlobalEventExecutor.INSTANCE);
            }
        } catch (Throwable ignore) {
            // Ignore the error as the underlying channel may be closed in the meantime and so
            // getSoLinger() may produce an exception. In this case we just return null.
            // See https://github.com/netty/netty/issues/4449
        }
        return null;
    }
}
