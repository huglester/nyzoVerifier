package co.nyzo.verifier;

import co.nyzo.verifier.util.IpUtil;
import co.nyzo.verifier.util.NotificationUtil;
import co.nyzo.verifier.util.PrintUtil;

import java.nio.ByteBuffer;
import java.util.*;

public class NodeManager {

    private static final Map<ByteBuffer, Node> ipAddressToNodeMap = new HashMap<>();
    private static final Map<ByteBuffer, Node> ipAddressToNodeMapInactive = new HashMap<>();

    private static final int consecutiveFailuresBeforeRemoval = 6;
    private static final Map<ByteBuffer, Integer> ipAddressToFailureCountMap = new HashMap<>();

    public static void updateNode(byte[] identifier, byte[] ipAddress, int port) {

        updateNode(identifier, ipAddress, port, 0);
    }

    public static synchronized void updateNode(byte[] identifier, byte[] ipAddress, int port, long queueTimestamp) {

        System.out.println("adding node " + PrintUtil.compactPrintByteArray(identifier) + ", " +
                IpUtil.addressAsString(ipAddress));

        if (identifier != null && identifier.length == FieldByteSize.identifier && ipAddress != null &&
                ipAddress.length == FieldByteSize.ipAddress) {

            // This logic enforces much of the one-verifier-per-IP rule.

            // First, try to get the node from the active map.
            ByteBuffer ipAddressBuffer = ByteBuffer.wrap(ipAddress);
            Node existingNode = ipAddressToNodeMap.get(ipAddressBuffer);

            // If the node is not in the active map, try to get it from the inactive map. If present, add it back
            // to the active map.
            if (existingNode == null) {
                existingNode = ipAddressToNodeMapInactive.remove(ipAddressBuffer);
                if (existingNode != null) {
                    ipAddressToNodeMap.put(ipAddressBuffer, existingNode);
                    System.out.println("moved verifier from inactive to active");
                }
            }

            if (existingNode == null) {
                // This is the simple case. If no other verifier is at this IP, add the verifier.
                Node node = new Node(identifier, ipAddress, port);
                if (queueTimestamp > 0) {
                    node.setQueueTimestamp(queueTimestamp);
                }
                ipAddressToNodeMap.put(ipAddressBuffer, node);
            } else if (ByteUtil.arraysAreEqual(identifier, existingNode.getIdentifier())) {
                // If the identifiers are the same, update the port.
                existingNode.setPort(port);
            } else {
                // This is the case of a new verifier taking over an existing IP address. This is where we are most
                // likely to have manipulation. This is allowed, but only if the existing verifier at this IP did not
                // verify a block in the previous two cycles.
                if (!BlockManager.verifierPresentInPreviousTwoCycles(existingNode.getIdentifier())) {
                    Node node = new Node(identifier, ipAddress, port);
                    if (queueTimestamp > 0) {
                        node.setQueueTimestamp(queueTimestamp);
                    }
                    ipAddressToNodeMap.put(ipAddressBuffer, node);
                }
            }
        }
    }

    public static synchronized List<Node> getMesh() {
        return new ArrayList<>(ipAddressToNodeMap.values());
    }

    public static boolean connectedToMesh() {

        // When we request the node list from another node, it will add this node to the list. So, the minimum number
        // of nodes in a proper mesh is two.
        return ipAddressToNodeMap.size() > 1;
    }

    public static byte[] identifierForIpAddress(String addressString) {

        byte[] identifier = null;
        byte[] address = IpUtil.addressFromString(addressString);
        if (address != null) {
            ByteBuffer addressBuffer = ByteBuffer.wrap(address);
            Node node = ipAddressToNodeMap.get(addressBuffer);
            if (node != null) {
                identifier = node.getIdentifier();
            }
        }

        return identifier;
    }

    public static void markFailedConnection(String addressString) {

        byte[] address = IpUtil.addressFromString(addressString);
        if (address != null) {
            ByteBuffer addressBuffer = ByteBuffer.wrap(address);
            Integer count = ipAddressToFailureCountMap.get(addressBuffer);
            if (count == null) {
                count = 1;
            } else {
                count++;
            }

            if (count < consecutiveFailuresBeforeRemoval) {
                ipAddressToFailureCountMap.put(addressBuffer, count);
            } else {
                ipAddressToFailureCountMap.remove(addressBuffer);
                removeNodeFromMesh(addressBuffer);
            }
        }
    }

    public static void markSuccessfulConnection(String addressString) {

        byte[] address = IpUtil.addressFromString(addressString);
        if (address != null) {
            ByteBuffer addressBuffer = ByteBuffer.wrap(address);
            ipAddressToFailureCountMap.remove(addressBuffer);
        }
    }

    private static synchronized void removeNodeFromMesh(ByteBuffer addressBuffer) {

        // If a node has verified in the past two cycles, we keep a record of it in the inactive map. This protects
        // against the verifier jumping in and out of the network to allow multiple verifiers at the same IP address.
        Node node = ipAddressToNodeMap.remove(addressBuffer);
        if (node != null) {
            NotificationUtil.send("removing node " + NicknameManager.get(node.getIdentifier()) + " from " +
                    "mesh of node " + Verifier.getNickname());

            if (BlockManager.verifierPresentInPreviousTwoCycles(node.getIdentifier())) {
                ipAddressToNodeMapInactive.put(addressBuffer, node);
            } else {
                System.out.println("not adding to inactive because " +
                        PrintUtil.compactPrintByteArray(node.getIdentifier()) + " is not a recent verifier");
            }

            // This is a good place to remove verifiers that no longer need to be in the inactive map.
            cleanInactiveMap();
        } else {
            System.out.println("not adding to inactive because node is null");
        }
    }

    private static synchronized void cleanInactiveMap() {

        Set<ByteBuffer> addressesInMap = new HashSet<>(ipAddressToNodeMapInactive.keySet());
        for (ByteBuffer address : addressesInMap) {
            Node node = ipAddressToNodeMapInactive.get(address);
            if (node != null && !BlockManager.verifierPresentInPreviousTwoCycles(node.getIdentifier())) {
                ipAddressToNodeMapInactive.remove(address);
                System.out.println("removed inactive node in cleanup");
            }
        }
    }

    // This method is temporary, for testing.
    public static int numberOfInactiveNodes() {
        return ipAddressToNodeMapInactive.size();
    }
}
