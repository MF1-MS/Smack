/**
 *
 * Copyright 2021 Microsoft Corporation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jivesoftware.smackx.muc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jivesoftware.smack.DummyConnection;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.MessageBuilder;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.test.util.SmackTestSuite;
import org.jivesoftware.smack.test.util.WaitForPacketListener;
import org.jivesoftware.smackx.muc.packet.GroupChatInvitation;
import org.jivesoftware.smackx.muc.packet.MUCUser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.EntityJid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.stringprep.XmppStringprepException;

/**
 * A test for for following features:
 *    <li>Adds support for Direct MUC invitations (see <a href="https://xmpp.org/extensions/xep-0249.html">XEP-0249</a>), which allows offline users to be invited to group chats.</li>
 * </ul>
 */
public class MultiUserChatTest extends SmackTestSuite {
    private static final int RESPONSE_TIMEOUT_IN_MILLIS = 10000;

    private DummyConnection connection;
    private MultiUserChatManager multiUserChatManager;

    @BeforeEach
    public void setUp() throws Exception {
        connection = new DummyConnection();
        connection.connect();
        connection.login();

        multiUserChatManager = MultiUserChatManager.getInstanceFor(connection);
    }

    @AfterEach
    public void tearDown() {
        if (connection != null) {
            connection.disconnect();
            connection = null;
        }
    }

    @Test
    public void testInviteDirectly() throws Throwable {
        EntityBareJid roomJid = JidCreate.entityBareFrom("room@example.com");
        EntityBareJid userJid = JidCreate.entityBareFrom("user@example.com");

        AtomicBoolean updateRequestSent = new AtomicBoolean();
        InvokeDirectlyResponder serverSimulator = new InvokeDirectlyResponder() {
            @Override
            void verifyRequest(Message updateRequest) {
                assertEquals(userJid, updateRequest.getTo(), "The provided JID doesn't match the request!");

                GroupChatInvitation groupChatInvitation = (GroupChatInvitation) updateRequest.getExtension(GroupChatInvitation.NAMESPACE);
                assertNotNull(groupChatInvitation, "Missing GroupChatInvitation extension");
                assertEquals(roomJid.toString(), groupChatInvitation.getRoomAddress());

                updateRequestSent.set(true);
            }
        };
        serverSimulator.start();

        // Create multi user chat
        MultiUserChat multiUserChat = multiUserChatManager.getMultiUserChat(roomJid);

        // Call tested method
        multiUserChat.inviteDirectly(userJid);

        // Wait for processing requests
        serverSimulator.join(RESPONSE_TIMEOUT_IN_MILLIS);
        serverSimulator.complete();

        // Check if an error occurred within the simulator
        final Throwable exception = serverSimulator.getException();
        if (exception != null) {
            throw exception;
        }

        assertTrue(updateRequestSent.get(), "Invite directly request not sent");
    }

    @Test
    public void shouldReceiveOfflineInvitation() throws XmppStringprepException {
        EntityBareJid roomJid = JidCreate.entityBareFrom("room@example.com");
        EntityFullJid inviterJid = JidCreate.entityFullFrom("inviter@example.com/user1");
        EntityBareJid inviteeJid = JidCreate.entityBareFrom("invitee@example.com");

        GroupChatInvitation groupChatInvitation = new GroupChatInvitation("room@example.com");
        Message sentMessage = MessageBuilder.buildMessage(UUID.randomUUID().toString())
                .from(inviterJid)
                .to(inviteeJid)
                .addExtension(groupChatInvitation)
                .build();

        // Prepare listener to receive a group invitation
        GroupInvitationListener groupInvitationListener = new GroupInvitationListener() {
            @Override
            public void verifyInvitation(XMPPConnection conn, MultiUserChat room, EntityJid inviter,
                                         String reason, String password, Message message, MUCUser.Invite invitation) {
                // Check all parameters values.
                assertSame(connection, conn);
                assertSame(connection, room.getXmppConnection());
                assertEquals(roomJid, room.getRoom());
                assertEquals(inviterJid, inviter);
                assertNull(reason);
                assertNull(password);
                assertSame(sentMessage, message);
                assertNull(invitation);
            }
        };
        multiUserChatManager.addInvitationListener(groupInvitationListener);

        // Simulate sending a message with a group invitation
        connection.processStanza(sentMessage);

        // Wait for the listener to be called or throw a timeout exception
        groupInvitationListener.waitUntilInvocationOrTimeout();
    }

    private static class Responder extends Thread {
        protected final AtomicBoolean running = new AtomicBoolean();
        protected Throwable exception;

        /**
         * Set the flag to false to make the while loop complete.
         */
        void complete() {
            running.set(false);
        }

        /**
         * Returns the exception or error if something went wrong.
         *
         * @return the Throwable exception or error that occurred.
         */
        Throwable getException() {
            return exception;
        }
    }

    /**
     * This class can be used to simulate the server response for invoke directly request.
     */
    private abstract class InvokeDirectlyResponder extends Responder {
        abstract void verifyRequest(Message updateRequest);

        @Override
        public void run() {
            try {
                while (true) {
                    final Stanza packet = connection.getSentPacket();
                    if (packet instanceof Message) {
                        Message message = (Message) packet;
                        verifyRequest(message);
                        break;
                    }
                }
            }
            catch (Throwable e) {
                exception = e;
            }
        }
    }

    /**
     * This class can be used to simulate receiving an invitation.
     */
    private abstract static class GroupInvitationListener extends WaitForPacketListener implements InvitationListener {
        public abstract void verifyInvitation(XMPPConnection conn, MultiUserChat room, EntityJid inviter,
                                       String reason, String password, Message message, MUCUser.Invite invitation);

        @Override
        public void invitationReceived(XMPPConnection conn, MultiUserChat room, EntityJid inviter,
                                       String reason, String password, Message message, MUCUser.Invite invitation) {
            verifyInvitation(conn, room, inviter, reason, password, message, invitation);
            reportInvoked();
        }
    }
}
