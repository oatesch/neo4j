/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.discovery;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import com.hazelcast.config.MemberAttributeConfig;
import com.hazelcast.core.Client;
import com.hazelcast.core.ClientService;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.HazelcastInstanceAware;
import com.hazelcast.core.IExecutorService;
import com.hazelcast.core.IMap;
import com.hazelcast.core.Member;

import org.neo4j.coreedge.core.CoreEdgeClusterSettings;
import org.neo4j.coreedge.edge.EnterpriseEdgeEditionModule;
import org.neo4j.coreedge.identity.MemberId;
import org.neo4j.coreedge.messaging.address.AdvertisedSocketAddress;
import org.neo4j.helpers.collection.Pair;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.logging.Log;

import static java.util.Collections.emptySet;
import static java.util.stream.Collectors.toSet;

class HazelcastClusterTopology
{
    // hz client uuid string -> boltAddress string
    static final String EDGE_SERVER_BOLT_ADDRESS_MAP_NAME = "edge-servers";
    static final String MEMBER_UUID = "member_uuid";
    static final String TRANSACTION_SERVER = "transaction_server";
    static final String RAFT_SERVER = "raft_server";
    static final String BOLT_SERVER = "bolt_server";

    static ClusterTopology getClusterTopology( HazelcastInstance hazelcastInstance, Log log )
    {
        Set<Member> coreMembers = emptySet();
        if ( hazelcastInstance != null )
        {
            coreMembers = hazelcastInstance.getCluster().getMembers();
        }
        return new ClusterTopology( canBeBootstrapped( coreMembers ), toCoreMemberMap( coreMembers, log ),
                edgeMembers( hazelcastInstance, log ) );
    }

    static class GetConnectedClients implements Callable<Collection<String>>, Serializable, HazelcastInstanceAware
    {
        private transient HazelcastInstance instance;

        GetConnectedClients( HazelcastInstance instance )
        {
            this.instance = instance;
        }

        @Override
        public Collection<String> call() throws Exception
        {
            final ClientService clientService = instance.getClientService();
            return clientService.getConnectedClients()
                    .stream()
                    .map( Client::getUuid )
                    .collect( Collectors.toCollection( HashSet::new ) );
        }

        @Override
        public void setHazelcastInstance( HazelcastInstance hazelcastInstance )
        {
            this.instance = hazelcastInstance;
        }
    }

    private static Set<EdgeAddresses> edgeMembers( HazelcastInstance hazelcastInstance, Log log )
    {
        if ( hazelcastInstance == null )
        {
            log.info( "Cannot currently bind to distributed discovery service." );
            return emptySet();
        }

        Collection<String> connectedUUIDs;
        final IExecutorService executorService = hazelcastInstance.getExecutorService( "default" );
        try
        {
            connectedUUIDs = executorService.submit( new GetConnectedClients( hazelcastInstance ) ).get();
            removeDisconnectedEdgeServers( hazelcastInstance, connectedUUIDs );

        }
        catch ( InterruptedException | ExecutionException e )
        {
            log.info( "Unable to complete operation with distributed discovery service.", e );
            return emptySet();
        }

        return hazelcastInstance.<String/*uuid*/, String/*boltAddress*/>getMap( EDGE_SERVER_BOLT_ADDRESS_MAP_NAME )
                .entrySet().stream().
                        filter( entry -> connectedUUIDs.contains( entry.getKey() ) )
                .map( entry -> new EdgeAddresses( new AdvertisedSocketAddress( entry.getValue() /*boltAddress*/ ) ) )
                .collect( toSet() );
    }

    private static void removeDisconnectedEdgeServers( HazelcastInstance hazelcastInstance,
                                                       Collection<String> connectedUUIDs )
    {
        IMap<String, String> edgeServers =
                hazelcastInstance.<String, String>getMap( EDGE_SERVER_BOLT_ADDRESS_MAP_NAME );

        Set<String> toRemove = edgeServers.keySet()
                .stream()
                .filter( key -> !connectedUUIDs.contains( key ) )
                .collect( Collectors.toSet() );

        toRemove.forEach( edgeServers::remove );
    }

    private static boolean canBeBootstrapped( Set<Member> coreMembers )
    {
        Iterator<Member> iterator = coreMembers.iterator();
        return iterator.hasNext() && iterator.next().localMember();
    }

    static Map<MemberId, CoreAddresses> toCoreMemberMap( Set<Member> members, Log log )
    {
        Map<MemberId, CoreAddresses> coreMembers = new HashMap<>();

        for ( Member member : members )
        {
            try
            {
                Pair<MemberId, CoreAddresses> pair = extractMemberAttributes( member );
                coreMembers.put( pair.first(), pair.other() );
            }
            catch ( IllegalArgumentException e )
            {
                log.warn( "Incomplete member attributes supplied from Hazelcast", e );
            }
        }

        return coreMembers;
    }

    static MemberAttributeConfig buildMemberAttributes( MemberId myself, Config config )
    {
        MemberAttributeConfig memberAttributeConfig = new MemberAttributeConfig();
        memberAttributeConfig.setStringAttribute( MEMBER_UUID, myself.getUuid().toString() );

        AdvertisedSocketAddress transactionSource = config.get( CoreEdgeClusterSettings
                .transaction_advertised_address );
        memberAttributeConfig.setStringAttribute( TRANSACTION_SERVER, transactionSource.toString() );

        AdvertisedSocketAddress raftAddress = config.get( CoreEdgeClusterSettings.raft_advertised_address );
        memberAttributeConfig.setStringAttribute( RAFT_SERVER, raftAddress.toString() );

        AdvertisedSocketAddress boltAddress = EnterpriseEdgeEditionModule.extractBoltAddress( config );
        memberAttributeConfig.setStringAttribute( BOLT_SERVER, boltAddress.toString() );
        return memberAttributeConfig;
    }

    static Pair<MemberId, CoreAddresses> extractMemberAttributes( Member member )
    {
        MemberId memberId = new MemberId( UUID.fromString( member.getStringAttribute( MEMBER_UUID ) ) );

        return Pair.of( memberId, new CoreAddresses(
                new AdvertisedSocketAddress( member.getStringAttribute( RAFT_SERVER ) ),
                new AdvertisedSocketAddress( member.getStringAttribute( TRANSACTION_SERVER ) ),
                new AdvertisedSocketAddress( member.getStringAttribute( BOLT_SERVER ) )
        ) );
    }
}
