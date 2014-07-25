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
package uk.co.real_logic.aeron.conductor;

import org.junit.Test;
import uk.co.real_logic.aeron.common.command.PublicationMessageFlyweight;
import uk.co.real_logic.aeron.common.command.QualifiedMessageFlyweight;
import uk.co.real_logic.aeron.common.command.SubscriptionMessageFlyweight;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.common.concurrent.MessageHandler;
import uk.co.real_logic.aeron.common.concurrent.ringbuffer.ManyToOneRingBuffer;
import uk.co.real_logic.aeron.common.concurrent.ringbuffer.RingBuffer;

import java.nio.ByteBuffer;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static uk.co.real_logic.aeron.common.command.ControlProtocolEvents.*;
import static uk.co.real_logic.aeron.common.concurrent.ringbuffer.RingBufferDescriptor.TRAILER_LENGTH;

public class DriverProxyTest
{
    public static final String DESTINATION = "udp://localhost:40123@localhost:40124";

    private static final int CHANNEL_ID = 1;
    private static final int SESSION_ID = 1;
    private final RingBuffer conductorBuffer =
        new ManyToOneRingBuffer(new AtomicBuffer(ByteBuffer.allocateDirect(TRAILER_LENGTH + 1024)));
    private final DriverProxy conductor = new DriverProxy(conductorBuffer);

    @Test
    public void threadSendsAddChannelMessage()
    {
        threadSendsChannelMessage(() -> conductor.addPublication(DESTINATION, 2, SESSION_ID), ADD_PUBLICATION);
    }

    @Test
    public void threadSendsRemoveChannelMessage()
    {
        threadSendsChannelMessage(() -> conductor.removePublication(DESTINATION, SESSION_ID, 2), REMOVE_PUBLICATION);
    }

    private void threadSendsChannelMessage(final Runnable sendMessage, final int expectedMsgTypeId)
    {
        sendMessage.run();

        assertReadsOneMessage(
            (msgTypeId, buffer, index, length) ->
            {
                final PublicationMessageFlyweight publicationMessage = new PublicationMessageFlyweight();
                publicationMessage.wrap(buffer, index);

                assertThat(msgTypeId, is(expectedMsgTypeId));
                assertThat(publicationMessage.destination(), is(DESTINATION));
                assertThat(publicationMessage.sessionId(), is(1));
                assertThat(publicationMessage.channelId(), is(2));
            }
        );
    }

    @Test
    public void threadSendsRemoveSubscriberMessage()
    {
        conductor.removeSubscription(DESTINATION, CHANNEL_ID);

        assertReadsOneMessage(
            (msgTypeId, buffer, index, length) ->
            {
                final SubscriptionMessageFlyweight subscriberMessage = new SubscriptionMessageFlyweight();
                subscriberMessage.wrap(buffer, index);

                assertThat(msgTypeId, is(REMOVE_SUBSCRIPTION));
                assertThat(subscriberMessage.destination(), is(DESTINATION));
                assertThat(subscriberMessage.channelId(), is(CHANNEL_ID));
            }
        );
    }

    @Test
    public void threadSendsRequestTermBufferMessage()
    {
        conductor.requestTerm(DESTINATION, SESSION_ID, 2, 3);

        assertReadsOneMessage(
            (msgTypeId, buffer, index, length) ->
            {
                final QualifiedMessageFlyweight qualifiedMessage = new QualifiedMessageFlyweight();
                qualifiedMessage.wrap(buffer, index);

                assertThat(msgTypeId, is(CLEAN_TERM_BUFFER));
                assertThat(qualifiedMessage.sessionId(), is(1));
                assertThat(qualifiedMessage.channelId(), is(2));
                assertThat(qualifiedMessage.destination(), is(DESTINATION));
                assertThat(qualifiedMessage.termId(), is(3));
            }
        );
    }

    private void assertReadsOneMessage(final MessageHandler handler)
    {
        final int messageCount = conductorBuffer.read(handler);
        assertThat(messageCount, is(1));
    }
}