//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.junit.jupiter.api.AfterEach;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


public class SslConnectionTest
{
    private static SslContextFactory __sslCtxFactory=new SslContextFactory();
    private static ByteBufferPool __byteBufferPool = new LeakTrackingByteBufferPool(new MappedByteBufferPool.Tagged());

    protected volatile EndPoint _lastEndp;
    private volatile boolean _testFill=true;
    private volatile FutureCallback _writeCallback;
    protected ServerSocketChannel _connector;
    final AtomicInteger _dispatches = new AtomicInteger();
    protected QueuedThreadPool _threadPool = new QueuedThreadPool()
    {
        @Override
        public void execute(Runnable job)
        {
            _dispatches.incrementAndGet();
            super.execute(job);
        }

    };
    protected Scheduler _scheduler = new TimerScheduler();
    protected SelectorManager _manager = new SelectorManager(_threadPool, _scheduler)
    {
        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            SSLEngine engine = __sslCtxFactory.newSSLEngine();
            engine.setUseClientMode(false);
            SslConnection sslConnection = new SslConnection(__byteBufferPool, getExecutor(), endpoint, engine);
            sslConnection.setRenegotiationAllowed(__sslCtxFactory.isRenegotiationAllowed());
            sslConnection.setRenegotiationLimit(__sslCtxFactory.getRenegotiationLimit());
            Connection appConnection = new TestConnection(sslConnection.getDecryptedEndPoint());
            sslConnection.getDecryptedEndPoint().setConnection(appConnection);
            return sslConnection;
        }


        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
        {
            SocketChannelEndPoint endp = new TestEP(channel, selector, selectionKey, getScheduler());
            endp.setIdleTimeout(60000);
            _lastEndp=endp;
            return endp;
        }
    };

    static final AtomicInteger __startBlocking = new AtomicInteger();
    static final AtomicInteger __blockFor = new AtomicInteger();
    private static class TestEP extends SocketChannelEndPoint
    {
        public TestEP(SelectableChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super((SocketChannel)channel,selector,key,scheduler);
        }

        @Override
        protected void onIncompleteFlush()
        {
            super.onIncompleteFlush();
        }
        

        @Override
        public boolean flush(ByteBuffer... buffers) throws IOException
        {
            if (__startBlocking.get()==0 || __startBlocking.decrementAndGet()==0)
            {
                if (__blockFor.get()>0 && __blockFor.getAndDecrement()>0)
                {
                    return false;
                }
            }
            boolean flushed=super.flush(buffers);
            return flushed;
        }
    }
    

    @BeforeAll
    public static void initSslEngine() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        __sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
        __sslCtxFactory.setKeyStorePassword("storepwd");
        __sslCtxFactory.setKeyManagerPassword("keypwd");
        __sslCtxFactory.start();
    }

    @BeforeEach
    public void startManager() throws Exception
    {
        _testFill=true;
        _writeCallback=null;
        _lastEndp=null;
        _connector = ServerSocketChannel.open();
        _connector.socket().bind(null);
        _threadPool.start();
        _scheduler.start();
        _manager.start();
        __sslCtxFactory.setRenegotiationAllowed(true);
        __sslCtxFactory.setRenegotiationLimit(-1);

    }

    @AfterEach
    public void stopManager() throws Exception
    {
        if (_lastEndp.isOpen())
            _lastEndp.close();
        _manager.stop();
        _scheduler.stop();
        _threadPool.stop();
        _connector.close();
    }

    public class TestConnection extends AbstractConnection
    {
        ByteBuffer _in = BufferUtil.allocate(8*1024);

        public TestConnection(EndPoint endp)
        {
            super(endp, _threadPool);
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            if (_testFill)
                fillInterested();
            else
            {
                getExecutor().execute(new Runnable()
                {

                    @Override
                    public void run()
                    {
                        getEndPoint().write(_writeCallback,BufferUtil.toBuffer("Hello Client"));
                    }
                });
            }
        }

        @Override
        public void onClose()
        {
            super.onClose();
        }

        @Override
        public synchronized void onFillable()
        {
            EndPoint endp = getEndPoint();
            try
            {
                boolean progress=true;
                while(progress)
                {
                    progress=false;

                    // Fill the input buffer with everything available
                    int filled=endp.fill(_in);
                    while (filled>0)
                    {
                        progress=true;
                        filled=endp.fill(_in);
                    }

                    // Write everything
                    int l=_in.remaining();
                    if (l>0)
                    {
                        FutureCallback blockingWrite= new FutureCallback();
                        endp.write(blockingWrite,_in);
                        blockingWrite.get();
                    }

                    // are we done?
                    if (endp.isInputShutdown())
                    {
                        endp.shutdownOutput();
                    }
                }
            }
            catch(InterruptedException|EofException e)
            {
                Log.getRootLogger().ignore(e);
            }
            catch(Exception e)
            {
                Log.getRootLogger().warn(e);
            }
            finally
            {
                if (endp.isOpen())
                    fillInterested();
            }
        }
    }
    protected SSLSocket newClient() throws IOException
    {
        SSLSocket socket = __sslCtxFactory.newSslSocket();
        socket.connect(_connector.socket().getLocalSocketAddress());
        return socket;
    }

    @Test
    public void testHelloWorld() throws Exception
    {
        Socket client = newClient();
        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[1024];
        int len=client.getInputStream().read(buffer);
        assertEquals(5, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Hello");

        _dispatches.set(0);
        client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
        len=5;
        while(len>0)
            len-=client.getInputStream().read(buffer);

        client.close();
    }

    @Test
    public void testRenegotiate() throws Exception
    {
        SSLSocket client = newClient();
        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[1024];
        int len=client.getInputStream().read(buffer);
        assertEquals(5, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Hello");

        client.startHandshake();
        
        client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
        len=client.getInputStream().read(buffer);
        assertEquals(5, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "World");

        client.close();
    }

    @Test
    public void testRenegotiateNotAllowed() throws Exception
    {
        __sslCtxFactory.setRenegotiationAllowed(false);
        
        SSLSocket client = newClient();
        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[1024];
        int len=client.getInputStream().read(buffer);
        assertEquals(5, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Hello");

        client.startHandshake();
        
        client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
        assertThrows(SSLException.class, ()-> client.getInputStream().read(buffer));
    }

    @Test
    public void testRenegotiateLimit() throws Exception
    {
        __sslCtxFactory.setRenegotiationAllowed(true);
        __sslCtxFactory.setRenegotiationLimit(2);
        
        SSLSocket client = newClient();
        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        client.getOutputStream().write("Good".getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[1024];
        int len=client.getInputStream().read(buffer);
        assertEquals(4, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Good");
        
        client.startHandshake();

        client.getOutputStream().write("Bye".getBytes(StandardCharsets.UTF_8));
        len=client.getInputStream().read(buffer);
        assertEquals(3, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Bye");
        
        client.startHandshake();

        client.getOutputStream().write("Cruel".getBytes(StandardCharsets.UTF_8));
        len=client.getInputStream().read(buffer);
        assertEquals(5, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Cruel");

        client.startHandshake();
        
        client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
        assertThrows(SSLException.class, ()-> client.getInputStream().read(buffer));
    }
    
    

    @Test
    public void testWriteOnConnect() throws Exception
    {
        _testFill=false;

        _writeCallback = new FutureCallback();
        Socket client = newClient();
        client.setSoTimeout(10000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        byte[] buffer = new byte[1024];
        int len=client.getInputStream().read(buffer);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Hello Client");
        assertEquals(null,_writeCallback.get(100,TimeUnit.MILLISECONDS));
        client.close();
    }
    


    @Test
    public void testBlockedWrite() throws Exception
    {
        Socket client = newClient();
        client.setSoTimeout(5000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        __startBlocking.set(5);
        __blockFor.set(3);
        
        client.getOutputStream().write("Hello".getBytes(StandardCharsets.UTF_8));
        byte[] buffer = new byte[1024];
        int len=client.getInputStream().read(buffer);
        assertEquals(5, len);
        assertEquals(new String(buffer,0,len, StandardCharsets.UTF_8), "Hello");

        _dispatches.set(0);
        client.getOutputStream().write("World".getBytes(StandardCharsets.UTF_8));
        len=5;
        while(len>0)
            len-=client.getInputStream().read(buffer);
        assertEquals(0, len);
        client.close();
    }

    @Test
    public void testManyLines() throws Exception
    {
        final Socket client = newClient();
        client.setSoTimeout(10000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        final int LINES=20;
        final CountDownLatch count=new CountDownLatch(LINES);


        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
                    while(count.getCount()>0)
                    {
                        String line=in.readLine();
                        if (line==null)
                            break;
                        // System.err.println(line);
                        count.countDown();
                    }
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
        }.start();

        for (int i=0;i<LINES;i++)
        {
            client.getOutputStream().write(("HelloWorld "+i+"\n").getBytes(StandardCharsets.UTF_8));
            // System.err.println("wrote");
            if (i%1000==0)
            {
                client.getOutputStream().flush();
                Thread.sleep(10);
            }
        }

        assertTrue(count.await(20,TimeUnit.SECONDS));
        client.close();

    }


}
