package org.buddycloud.channelserver.pubsub.model.impl;

import nl.jqno.equalsverifier.EqualsVerifier;

import org.junit.Test;

public class NodeSubscriptionImplTest {
	@Test
	public void testEquals() {
		EqualsVerifier.forClass(NodeSubscriptionImpl.class).verify();
	}

}
