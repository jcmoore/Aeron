/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.aeron.driver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.aeron.common.ErrorCode;
import uk.co.real_logic.aeron.common.TimerWheel;
import uk.co.real_logic.aeron.common.command.ControlProtocolEvents;
import uk.co.real_logic.aeron.common.command.PublicationMessageFlyweight;
import uk.co.real_logic.aeron.common.command.SubscriptionMessageFlyweight;
import uk.co.real_logic.aeron.common.concurrent.AtomicArray;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.common.concurrent.OneToOneConcurrentArrayQueue;
import uk.co.real_logic.aeron.common.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.aeron.common.concurrent.ringbuffer.RingBuffer;
import uk.co.real_logic.aeron.common.concurrent.ringbuffer.RingBufferDescriptor;
import uk.co.real_logic.aeron.common.event.EventLogger;
import uk.co.real_logic.aeron.common.status.CountersManager;
import uk.co.real_logic.aeron.driver.buffer.TermBuffersFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static uk.co.real_logic.aeron.common.ErrorCode.INVALID_DESTINATION;
import static uk.co.real_logic.aeron.common.ErrorCode.PUBLICATION_CHANNEL_UNKNOWN;
import static uk.co.real_logic.aeron.common.concurrent.logbuffer.LogBufferDescriptor.STATE_BUFFER_LENGTH;
import static uk.co.real_logic.aeron.driver.MediaDriver.CONDUCTOR_TICKS_PER_WHEEL;
import static uk.co.real_logic.aeron.driver.MediaDriver.CONDUCTOR_TICK_DURATION_US;

/**
 * Test the Media Driver Conductor in isolation
 *
 * Tests for interaction of media conductor with client protocol
 */
public class DriverConductorTest
{
    private static final String DESTINATION_URI = "udp://localhost:";
    private static final String INVALID_URI = "udp://";
    private static final int CHANNEL_1 = 10;
    private static final int CHANNEL_2 = 20;
    private static final int CHANNEL_3 = 30;
    private static final int TERM_BUFFER_SZ = MediaDriver.TERM_BUFFER_SZ_DEFAULT;
    private static final long CORRELATION_ID = 1429;

    private final ByteBuffer toDriverBuffer =
        ByteBuffer.allocate(MediaDriver.COMMAND_BUFFER_SZ + RingBufferDescriptor.TRAILER_LENGTH);

    private final NioSelector nioSelector = mock(NioSelector.class);
    private final TermBuffersFactory mockTermBuffersFactory = mock(TermBuffersFactory.class);

    private final RingBuffer fromClientCommands = new ManyToOneRingBuffer(new AtomicBuffer(toDriverBuffer));
    private final ClientProxy mockClientProxy = mock(ClientProxy.class);

    private final PublicationMessageFlyweight publicationMessage = new PublicationMessageFlyweight();

    private final SubscriptionMessageFlyweight subscriptionMessage = new SubscriptionMessageFlyweight();
    private final AtomicBuffer writeBuffer = new AtomicBuffer(ByteBuffer.allocate(256));

    private final AtomicArray<DriverPublication> publications = new AtomicArray<>();
    private final EventLogger mockConductorLogger = mock(EventLogger.class);
    private final EventLogger mockReceiverLogger = mock(EventLogger.class);

    private DriverConductor driverConductor;
    private Receiver receiver;

    @Before
    public void setUp() throws Exception
    {
        when(mockTermBuffersFactory.newPublication(anyObject(), anyInt(), anyInt()))
            .thenReturn(BufferAndFrameHelper.newTestTermBuffers(TERM_BUFFER_SZ, STATE_BUFFER_LENGTH));

        final AtomicBuffer counterBuffer = new AtomicBuffer(new byte[4096]);
        final CountersManager countersManager =
            new CountersManager(new AtomicBuffer(new byte[4096]), counterBuffer);

        final MediaDriver.DriverContext ctx = new MediaDriver.DriverContext()
            .receiverNioSelector(nioSelector)
            .conductorNioSelector(nioSelector)
            .unicastSenderFlowControl(UnicastSenderControlStrategy::new)
            .multicastSenderFlowControl(DefaultMulticastSenderControlStrategy::new)
            .conductorTimerWheel(new TimerWheel(CONDUCTOR_TICK_DURATION_US,
                                                TimeUnit.MICROSECONDS,
                                                CONDUCTOR_TICKS_PER_WHEEL))
            .conductorCommandQueue(new OneToOneConcurrentArrayQueue<>(1024))
            .receiverCommandQueue(new OneToOneConcurrentArrayQueue<>(1024))
            .publications(publications)

            .termBuffersFactory(mockTermBuffersFactory)
            .countersManager(countersManager)
            .conductorLogger(mockConductorLogger)
            .receiverLogger(mockReceiverLogger);

        ctx.fromClientCommands(fromClientCommands);
        ctx.clientProxy(mockClientProxy);
        ctx.countersBuffer(counterBuffer);

        ctx.receiverProxy(new ReceiverProxy(ctx.receiverCommandQueue()));
        ctx.driverConductorProxy(new DriverConductorProxy(ctx.conductorCommandQueue()));

        receiver = new Receiver(ctx);
        driverConductor = new DriverConductor(ctx);
    }

    @After
    public void tearDown() throws Exception
    {
        receiver.close();
        receiver.nioSelector().selectNowWithoutProcessing();
        driverConductor.close();
        driverConductor.nioSelector().selectNowWithoutProcessing();
    }

    @Test
    public void shouldBeAbleToAddSinglePublication() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4000);

        driverConductor.doWork();

        assertThat(publications.size(), is(1));
        assertNotNull(publications.get(0));
        assertThat(publications.get(0).sessionId(), is(1));
        assertThat(publications.get(0).channelId(), is(2));

        verify(mockClientProxy).onNewTermBuffers(eq(ControlProtocolEvents.ON_NEW_PUBLICATION),
                                                 eq(1), eq(2), anyInt(), eq(DESTINATION_URI + 4000),
                                                 any(), anyLong(), anyInt());
    }

    @Test
    public void shouldBeAbleToAddSingleSubscription() throws Exception
    {
        EventLogger.logInvocation();

        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_1);

        driverConductor.doWork();
        receiver.doWork();

        verify(mockClientProxy).operationSucceeded(CORRELATION_ID);

        assertNotNull(driverConductor.subscriptionMediaEndpoint(UdpDestination.parse(DESTINATION_URI + 4000)));
    }

    @Test
    public void shouldBeAbleToAddAndRemoveSingleSubscription() throws Exception
    {
        EventLogger.logInvocation();

        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_1);
        writeSubscriberMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_1);

        driverConductor.doWork();
        receiver.doWork();

        assertNull(driverConductor.subscriptionMediaEndpoint(UdpDestination.parse(DESTINATION_URI + 4000)));
    }

    @Test
    public void shouldBeAbleToAddMultipleChannels() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4001);
        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 3, 4002);
        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 3, 2, 4003);
        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 3, 4, 4004);

        driverConductor.doWork();

        assertThat(publications.size(), is(4));
    }

    @Test
    public void shouldBeAbleToRemoveSingleChannel() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4005);
        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 1, 2, 4005);

        driverConductor.doWork();

        assertThat(publications.size(), is(0));
        assertNull(driverConductor.publicationMediaEndpoint(UdpDestination.parse(DESTINATION_URI + 4005)));
    }

    @Test
    public void shouldBeAbleToRemoveMultipleChannels() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4006);
        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 3, 4007);
        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 3, 2, 4008);
        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 3, 4, 4008);

        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 1, 2, 4006);
        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 1, 3, 4007);
        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 3, 2, 4008);
        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 3, 4, 4008);

        driverConductor.doWork();

        assertThat(publications.size(), is(0));
    }

    @Test
    public void shouldKeepSubscriptionMediaEndpointUponRemovalOfAllButOneSubscriber() throws Exception
    {
        EventLogger.logInvocation();

        final UdpDestination destination = UdpDestination.parse(DESTINATION_URI + 4000);

        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_1);
        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_2);
        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_3);

        driverConductor.doWork();
        receiver.doWork();

        final MediaSubscriptionEndpoint mediaEndpoint = driverConductor.subscriptionMediaEndpoint(destination);

        assertNotNull(mediaEndpoint);
        assertThat(mediaEndpoint.channelCount(), is(3));

        writeSubscriberMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_1);
        writeSubscriberMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_2);

        driverConductor.doWork();
        receiver.doWork();

        assertNotNull(driverConductor.subscriptionMediaEndpoint(destination));
        assertThat(mediaEndpoint.channelCount(), is(1));
    }

    @Test
    public void shouldOnlyRemoveSubscriptionMediaEndpointUponRemovalOfAllSubscribers() throws Exception
    {
        EventLogger.logInvocation();

        final UdpDestination destination = UdpDestination.parse(DESTINATION_URI + 4000);

        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_1);
        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_2);
        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_3);

        driverConductor.doWork();
        receiver.doWork();

        final MediaSubscriptionEndpoint mediaEndpoint = driverConductor.subscriptionMediaEndpoint(destination);

        assertNotNull(mediaEndpoint);
        assertThat(mediaEndpoint.channelCount(), is(3));

        writeSubscriberMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_2);
        writeSubscriberMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_3);

        driverConductor.doWork();
        receiver.doWork();

        assertNotNull(driverConductor.subscriptionMediaEndpoint(destination));
        assertThat(mediaEndpoint.channelCount(), is(1));

        writeSubscriberMessage(ControlProtocolEvents.REMOVE_SUBSCRIPTION, DESTINATION_URI + 4000, CHANNEL_1);

        driverConductor.doWork();
        receiver.doWork();

        assertNull(driverConductor.subscriptionMediaEndpoint(destination));
    }

    @Test
    public void shouldErrorOnAddDuplicateChannelOnExistingSession() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4000);
        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4000);

        driverConductor.doWork();

        assertThat(publications.size(), is(1));
        assertNotNull(publications.get(0));
        assertThat(publications.get(0).sessionId(), is(1));
        assertThat(publications.get(0).channelId(), is(2));

        verify(mockClientProxy)
            .onError(eq(ErrorCode.PUBLICATION_CHANNEL_ALREADY_EXISTS), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    @Test
    public void shouldErrorOnRemoveChannelOnUnknownDestination() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 1, 2, 4000);

        driverConductor.doWork();

        assertThat(publications.size(), is(0));

        verify(mockClientProxy).onError(eq(INVALID_DESTINATION), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    @Test
    public void shouldErrorOnRemoveChannelOnUnknownSessionId() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4000);
        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 2, 2, 4000);

        driverConductor.doWork();

        assertThat(publications.size(), is(1));
        assertNotNull(publications.get(0));
        assertThat(publications.get(0).sessionId(), is(1));
        assertThat(publications.get(0).channelId(), is(2));

        verify(mockClientProxy).onError(eq(PUBLICATION_CHANNEL_UNKNOWN), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    @Test
    public void shouldErrorOnRemoveChannelOnUnknownChannelId() throws Exception
    {
        EventLogger.logInvocation();

        writeChannelMessage(ControlProtocolEvents.ADD_PUBLICATION, 1, 2, 4000);
        writeChannelMessage(ControlProtocolEvents.REMOVE_PUBLICATION, 1, 3, 4000);

        driverConductor.doWork();

        assertThat(publications.size(), is(1));
        assertNotNull(publications.get(0));
        assertThat(publications.get(0).sessionId(), is(1));
        assertThat(publications.get(0).channelId(), is(2));

        verify(mockClientProxy).onError(eq(PUBLICATION_CHANNEL_UNKNOWN), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    @Test
    public void shouldErrorOnAddSubscriptionWithInvalidUri() throws Exception
    {
        EventLogger.logInvocation();

        writeSubscriberMessage(ControlProtocolEvents.ADD_SUBSCRIPTION, INVALID_URI, CHANNEL_1);

        driverConductor.doWork();
        receiver.doWork();
        driverConductor.doWork();

        verify(mockClientProxy).onError(eq(INVALID_DESTINATION), argThat(not(isEmptyOrNullString())), any(), anyInt());
        verifyNeverSucceeds();
        verifyExceptionLogged();
    }

    private void verifyExceptionLogged()
    {
        verify(mockConductorLogger).logException(any());
    }

    private void verifyNeverSucceeds()
    {
        verify(mockClientProxy, never()).operationSucceeded(anyLong());
    }

    private void writeChannelMessage(final int msgTypeId, final int sessionId, final int channelId, final int port)
        throws IOException
    {
        publicationMessage.wrap(writeBuffer, 0);
        publicationMessage.channelId(channelId);
        publicationMessage.sessionId(sessionId);
        publicationMessage.destination(DESTINATION_URI + port);

        fromClientCommands.write(msgTypeId, writeBuffer, 0, publicationMessage.length());
    }

    private void writeSubscriberMessage(final int msgTypeId, final String destination, final int channelId)
        throws IOException
    {
        subscriptionMessage.wrap(writeBuffer, 0);
        subscriptionMessage.channelId(channelId)
                           .destination(destination)
                           .correlationId(CORRELATION_ID);

        fromClientCommands.write(msgTypeId, writeBuffer, 0, subscriptionMessage.length());
    }
}