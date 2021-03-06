/**
 * Copyright (c) 2014-2017 by the respective copyright holders.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.binding.lifx.internal;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang.StringUtils;
import org.eclipse.smarthome.binding.lifx.LifxBindingConstants;
import org.eclipse.smarthome.binding.lifx.internal.fields.MACAddress;
import org.eclipse.smarthome.binding.lifx.internal.protocol.GetLabelRequest;
import org.eclipse.smarthome.binding.lifx.internal.protocol.GetServiceRequest;
import org.eclipse.smarthome.binding.lifx.internal.protocol.GetVersionRequest;
import org.eclipse.smarthome.binding.lifx.internal.protocol.Packet;
import org.eclipse.smarthome.binding.lifx.internal.protocol.PacketFactory;
import org.eclipse.smarthome.binding.lifx.internal.protocol.PacketHandler;
import org.eclipse.smarthome.binding.lifx.internal.protocol.Products;
import org.eclipse.smarthome.binding.lifx.internal.protocol.StateLabelResponse;
import org.eclipse.smarthome.binding.lifx.internal.protocol.StateServiceResponse;
import org.eclipse.smarthome.binding.lifx.internal.protocol.StateVersionResponse;
import org.eclipse.smarthome.config.discovery.AbstractDiscoveryService;
import org.eclipse.smarthome.config.discovery.DiscoveryResult;
import org.eclipse.smarthome.config.discovery.DiscoveryResultBuilder;
import org.eclipse.smarthome.core.thing.ThingUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link LifxLightDiscovery} provides support for auto-discovery of LIFX
 * lights.
 *
 * @author Dennis Nobel - Initial contribution
 * @author Karel Goderis - Rewrite for Firmware V2, and remove dependency on external libraries
 * @author Wouter Born - Discover light labels, improve locking, optimize packet handling
 */
public class LifxLightDiscovery extends AbstractDiscoveryService {

    private final Logger logger = LoggerFactory.getLogger(LifxLightDiscovery.class);

    private static final int SERVICE_REQUEST_SEQ_NO = 0;
    private static final int VERSION_REQUEST_SEQ_NO = 1;
    private static final int LABEL_REQUEST_SEQ_NO = 2;

    private List<InetSocketAddress> broadcastAddresses;
    private List<InetAddress> interfaceAddresses;
    private final int BROADCAST_PORT = 56700;
    private static int REFRESH_INTERVAL = 60;
    private static int BROADCAST_TIMEOUT = 5000;
    private static int SELECTOR_TIMEOUT = 10000;
    private int bufferSize = 0;

    private Selector selector;
    private DatagramChannel broadcastChannel;
    private long source;
    private boolean isScanning = false;

    private ScheduledFuture<?> discoveryJob;
    private ScheduledFuture<?> networkJob;

    private Map<MACAddress, DiscoveredLight> discoveredLights = new HashMap<MACAddress, DiscoveredLight>();

    private class DiscoveredLight {

        private MACAddress macAddress;
        private InetSocketAddress socketAddress;
        private String label;
        private Products products;
        private long productVersion;
        private boolean supportedProduct = true;

        private long lastRequestTimeMillis;

        public DiscoveredLight(MACAddress macAddress, InetSocketAddress socketAddress) {
            this.macAddress = macAddress;
            this.socketAddress = socketAddress;
        }

        public boolean isDataComplete() {
            return label != null && products != null;
        }

    }

    public LifxLightDiscovery() throws IllegalArgumentException {
        super(LifxBindingConstants.SUPPORTED_THING_TYPES, 1, true);
    }

    @Override
    protected void activate(Map<String, Object> configProperties) {
        super.activate(configProperties);

        broadcastAddresses = new ArrayList<InetSocketAddress>();
        interfaceAddresses = new ArrayList<InetAddress>();

        Enumeration<NetworkInterface> networkInterfaces = null;
        try {
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
        } catch (SocketException e) {
            logger.debug("An exception occurred while discovering LIFX lights : '{}'", e.getMessage());
        }
        if (networkInterfaces != null) {
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface iface = networkInterfaces.nextElement();
                try {
                    if (iface.isUp() && !iface.isLoopback()) {
                        for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                            if (ifaceAddr.getAddress() instanceof Inet4Address) {
                                logger.debug("Adding '{}' as interface address with MTU {}", ifaceAddr.getAddress(),
                                        iface.getMTU());
                                if (iface.getMTU() > bufferSize) {
                                    bufferSize = iface.getMTU();
                                }
                                interfaceAddresses.add(ifaceAddr.getAddress());
                                if (ifaceAddr.getBroadcast() != null) {
                                    logger.debug("Adding '{}' as broadcast address", ifaceAddr.getBroadcast());
                                    broadcastAddresses
                                            .add(new InetSocketAddress(ifaceAddr.getBroadcast(), BROADCAST_PORT));
                                }
                            }
                        }
                    }
                } catch (SocketException e) {
                    logger.debug("An exception occurred while discovering LIFX lights : '{}'", e.getMessage());
                }
            }
        }
    }

    @Override
    protected void deactivate() {
        super.deactivate();
    }

    @Override
    protected void startBackgroundDiscovery() {
        logger.debug("Starting the LIFX device background discovery");

        Runnable discoveryRunnable = new Runnable() {
            @Override
            public void run() {
                doScan();
            }
        };

        if (discoveryJob == null || discoveryJob.isCancelled()) {
            discoveryJob = scheduler.scheduleWithFixedDelay(discoveryRunnable, 0, REFRESH_INTERVAL, TimeUnit.SECONDS);
        }
    }

    @Override
    protected void stopBackgroundDiscovery() {
        logger.debug("Stopping LIFX device background discovery");
        if (discoveryJob != null && !discoveryJob.isCancelled()) {
            discoveryJob.cancel(true);
            discoveryJob = null;
        }
        if (networkJob != null && !networkJob.isCancelled()) {
            networkJob.cancel(true);
            networkJob = null;
        }
    }

    @Override
    protected void startScan() {
        doScan();
    }

    @Override
    protected synchronized void stopScan() {
        super.stopScan();
        removeOlderResults(getTimestampOfLastScan());
    }

    protected void doScan() {

        try {

            if (!isScanning) {
                isScanning = true;
                if (selector != null) {
                    selector.close();
                }

                if (broadcastChannel != null) {
                    broadcastChannel.close();
                }

                selector = Selector.open();

                broadcastChannel = DatagramChannel.open(StandardProtocolFamily.INET)
                        .setOption(StandardSocketOptions.SO_REUSEADDR, true)
                        .setOption(StandardSocketOptions.SO_BROADCAST, true);
                broadcastChannel.configureBlocking(false);
                broadcastChannel.socket().setSoTimeout(BROADCAST_TIMEOUT);
                broadcastChannel.bind(new InetSocketAddress(BROADCAST_PORT));

                SelectionKey broadcastKey = broadcastChannel.register(selector,
                        SelectionKey.OP_READ | SelectionKey.OP_WRITE);

                networkJob = scheduler.schedule(networkRunnable, 0, TimeUnit.MILLISECONDS);

                source = UUID.randomUUID().getLeastSignificantBits() & (-1L >>> 32);
                logger.debug("The LIFX discovery service will use '{}' as source identifier",
                        Long.toString(source, 16));

                GetServiceRequest packet = new GetServiceRequest();
                packet.setSequence(SERVICE_REQUEST_SEQ_NO);
                packet.setSource(source);

                broadcastPacket(packet, broadcastKey);
            } else {
                logger.info("A discovery scan for LIFX light is already underway");
            }

        } catch (Exception e) {
            logger.debug("An exception occurred while discovering LIFX lights : '{}'", e.getMessage());
        }

    }

    private void broadcastPacket(Packet packet, SelectionKey broadcastKey) {
        for (InetSocketAddress address : broadcastAddresses) {
            LifxNetworkThrottler.lock();
            sendPacket(packet, address, broadcastKey);
            LifxNetworkThrottler.unlock();
        }
    }

    private void sendLightDataRequestPacket(DiscoveredLight light, Packet packet, int sequenceNumber,
            SelectionKey unicastKey) {
        packet.setTarget(light.macAddress);
        packet.setSequence(sequenceNumber);
        packet.setSource(source);

        LifxNetworkThrottler.lock(light.macAddress);
        sendPacket(packet, light.socketAddress, unicastKey);
        LifxNetworkThrottler.unlock(light.macAddress);
    }

    private boolean sendPacket(Packet packet, InetSocketAddress address, SelectionKey selectedKey) {

        boolean result = false;

        try {
            while (!result) {
                selector.selectNow();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (!result && keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();

                    if (key.isValid() && key.isWritable() && key.equals(selectedKey)) {
                        SelectableChannel channel = key.channel();
                        if (channel instanceof DatagramChannel) {
                            logger.trace(
                                    "Discovery : Sending packet type '{}' from '{}' to '{}' for '{}' with sequence '{}' and source '{}'",
                                    new Object[] { packet.getClass().getSimpleName(),
                                            ((InetSocketAddress) ((DatagramChannel) channel).getLocalAddress())
                                                    .toString(),
                                            address.toString(), packet.getTarget().getHex(), packet.getSequence(),
                                            Long.toString(packet.getSource(), 16) });
                            ((DatagramChannel) channel).send(packet.bytes(), address);
                            result = true;
                        } else if (channel instanceof SocketChannel) {
                            ((SocketChannel) channel).write(packet.bytes());
                            result = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("An exception occurred while sending a packet to the light : '{}'", e.getMessage());
        }

        return result;
    }

    private Runnable networkRunnable = new Runnable() {

        @Override
        public void run() {
            try {

                long startStamp = System.currentTimeMillis();
                discoveredLights.clear();

                logger.trace("Entering read loop at {}", startStamp);

                while (System.currentTimeMillis() - startStamp < SELECTOR_TIMEOUT) {

                    if (selector != null && selector.isOpen()) {
                        try {
                            selector.selectNow();
                        } catch (IOException e) {
                            logger.error("An exception occurred while selecting: {}", e.getMessage());
                        }

                        Set<SelectionKey> selectedKeys = selector.selectedKeys();
                        Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                        while (keyIterator.hasNext()) {

                            SelectionKey key = keyIterator.next();

                            if (key.isValid() && key.isAcceptable()) {
                                // a connection was accepted by a ServerSocketChannel.
                                // block of code only for completeness purposes

                            } else if (key.isValid() && key.isConnectable()) {
                                // a connection was established with a remote server.
                                // block of code only for completeness purposes

                            } else if (key.isValid() && key.isReadable()) {
                                // a channel is ready for reading
                                SelectableChannel channel = key.channel();
                                InetSocketAddress address = null;
                                int messageLength = 0;

                                ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
                                try {
                                    if (channel instanceof DatagramChannel) {
                                        address = (InetSocketAddress) ((DatagramChannel) channel).receive(readBuffer);
                                    } else if (channel instanceof SocketChannel) {
                                        address = (InetSocketAddress) ((SocketChannel) channel).getRemoteAddress();
                                        ((SocketChannel) channel).read(readBuffer);
                                    }
                                    messageLength = readBuffer.position();
                                } catch (Exception e) {
                                    logger.warn("An exception occurred while reading data : '{}'", e.getMessage());
                                }

                                if (address != null) {
                                    logger.trace("Receiving data from {}", address.getAddress().toString());
                                    if (!interfaceAddresses.contains(address.getAddress())) {

                                        readBuffer.rewind();

                                        ByteBuffer packetSize = readBuffer.slice();
                                        packetSize.position(0);
                                        packetSize.limit(2);
                                        int size = Packet.FIELD_SIZE.value(packetSize);

                                        if (messageLength == size) {

                                            ByteBuffer packetType = readBuffer.slice();
                                            packetType.position(32);
                                            packetType.limit(34);
                                            int type = Packet.FIELD_PACKET_TYPE.value(packetType);

                                            PacketHandler<?> handler = PacketFactory.createHandler(type);

                                            if (handler == null) {
                                                logger.trace("Unknown packet type: {} (source: {})",
                                                        String.format("0x%02X", type), address.toString());
                                                continue;
                                            }

                                            Packet packet = handler.handle(readBuffer);
                                            if (packet == null) {
                                                logger.warn("Handler {} was unable to handle packet",
                                                        handler.getClass().getName());
                                            } else {
                                                handlePacket(packet, address);
                                            }
                                        }
                                    }
                                } else if (key.isValid() && key.isWritable()) {
                                    // a channel is ready for writing
                                    // block of code only for completeness purposes
                                }
                            }
                        }

                        requestAdditionalLightData();
                    }
                }
                isScanning = false;
            } catch (Exception e) {
                logger.debug("An exception occurred while communicating with the light : '{}'", e.getMessage(), e);
            }
        }

        private void requestAdditionalLightData() throws IOException, ClosedChannelException {
            // Iterate through the discovered lights that have to be set up, and the packets that have to be sent
            // Workaround to avoid a ConcurrentModifictionException on the selector.SelectedKeys() Set
            for (DiscoveredLight light : discoveredLights.values()) {

                boolean waitingForLightResponse = System.currentTimeMillis() - light.lastRequestTimeMillis < 200;

                if (light.supportedProduct && !light.isDataComplete() && !waitingForLightResponse) {
                    DatagramChannel unicastChannel = DatagramChannel.open(StandardProtocolFamily.INET)
                            .setOption(StandardSocketOptions.SO_REUSEADDR, true);
                    unicastChannel.configureBlocking(false);
                    SelectionKey unicastKey = unicastChannel.register(selector,
                            SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    unicastChannel.connect(light.socketAddress);
                    logger.trace("Connected to a light via {}", unicastChannel.getLocalAddress().toString());

                    if (light.products == null) {
                        sendLightDataRequestPacket(light, new GetVersionRequest(), VERSION_REQUEST_SEQ_NO, unicastKey);
                    }

                    if (light.label == null) {
                        sendLightDataRequestPacket(light, new GetLabelRequest(), LABEL_REQUEST_SEQ_NO, unicastKey);
                    }

                    light.lastRequestTimeMillis = System.currentTimeMillis();
                }
            }
        }
    };

    private void handlePacket(Packet packet, InetSocketAddress address) {

        logger.trace("Discovery : Packet type '{}' received from '{}' for '{}' with sequence '{}' and source '{}'",
                new Object[] { packet.getClass().getSimpleName(), address.toString(), packet.getTarget().getHex(),
                        packet.getSequence(), Long.toString(packet.getSource(), 16) });

        if (packet.getSource() == source || packet.getSource() == 0) {

            DiscoveredLight light = discoveredLights.get(packet.getTarget());

            if (packet instanceof StateServiceResponse) {
                int port = (int) ((StateServiceResponse) packet).getPort();
                if (port != 0) {
                    try {
                        MACAddress macAddress = packet.getTarget();
                        InetSocketAddress socketAddress = new InetSocketAddress(address.getAddress(), port);
                        light = new DiscoveredLight(macAddress, socketAddress);
                        discoveredLights.put(macAddress, light);
                    } catch (Exception e) {
                        logger.warn("An exception occurred while connecting to IP address : '{}'", e.getMessage());
                        return;
                    }
                }
            } else if (packet instanceof StateLabelResponse) {
                light.label = ((StateLabelResponse) packet).getLabel().trim();
            } else if (packet instanceof StateVersionResponse) {
                try {
                    light.products = Products.getProductFromProductID(((StateVersionResponse) packet).getProduct());
                    light.productVersion = ((StateVersionResponse) packet).getVersion();
                } catch (IllegalArgumentException e) {
                    logger.debug("Discovered an unsupported light ({}): {}", light.macAddress.getAsLabel(),
                            e.getMessage());
                    light.supportedProduct = false;
                }
            }

            if (light != null && light.isDataComplete()) {
                DiscoveryResult discoveryResult = createDiscoveryResult(light);
                if (discoveryResult != null) {
                    thingDiscovered(discoveryResult);
                }
            }
        }
    }

    private DiscoveryResult createDiscoveryResult(DiscoveredLight light) {
        try {
            String macAsLabel = light.macAddress.getAsLabel();
            Products product = light.products;
            ThingUID thingUID = new ThingUID(product.getThingTypeUID(), macAsLabel);

            String label = light.label;
            if (StringUtils.isBlank(label)) {
                label = product.getName();
            }

            logger.trace("Discovered a LIFX light : {}", label);

            DiscoveryResultBuilder builder = DiscoveryResultBuilder.create(thingUID);
            builder.withRepresentationProperty(macAsLabel);
            builder.withLabel(label);

            builder.withProperty(LifxBindingConstants.CONFIG_PROPERTY_DEVICE_ID, macAsLabel);
            builder.withProperty(LifxBindingConstants.PROPERTY_MAC_ADDRESS, macAsLabel);
            builder.withProperty(LifxBindingConstants.PROPERTY_PRODUCT_ID, light.products.getProduct());
            builder.withProperty(LifxBindingConstants.PROPERTY_PRODUCT_NAME, light.products.getName());
            builder.withProperty(LifxBindingConstants.PROPERTY_PRODUCT_VERSION, light.productVersion);
            builder.withProperty(LifxBindingConstants.PROPERTY_VENDOR_ID, light.products.getVendor());
            builder.withProperty(LifxBindingConstants.PROPERTY_VENDOR_NAME, light.products.getVendorName());

            return builder.build();
        } catch (IllegalArgumentException e) {
            logger.trace("Ignoring packet: {}", e);
            return null;
        }
    }

}
