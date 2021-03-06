package inductionlib;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Timestamp;

/**
 *
 * This class understands the serial communication for my induction cooker
 */
public class InductionControl {

    public static enum Role {
        KEYBOARD,  // Act as the keyboard
        POWERCARD, // Act as the power card
        PASSIVE    // Act as a passive listener
    }

    private final PowerCardCallback powerCardCallback;
    private final KeyBoardCallback keyBoardCallback;

    // TODO Measure this!
    private static long MAX_ACK_WAIT_TIME = 100;
    private static int MAX_RETRIES = 5;

    // Must hold synch lock of "this" before access!
    private Byte ack = null;

    OutputStream os;
    private final Thread readThread;
    Role role;

    public InductionControl(
            final InputStream is,
            final OutputStream os,
            PowerCardCallback powerCardCallback,
            KeyBoardCallback keyBoardCallback,
            Role role) {
        this.powerCardCallback = powerCardCallback;
        this.keyBoardCallback = keyBoardCallback;
        this.os = os;
        this.role = role;
        readThread = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    while (true) {
                        int numBytes = 0;
                        numBytes = is.read(buffer, bufferSize, buffer.length
                                    - bufferSize);
                        bufferSize += numBytes;
                        //System.out.println("Read " + numBytes + " bufferSize=" + bufferSize);
                        decodeData();
                    }
                } catch (IOException e) {
                    // Will happen when is is closed
                    logDebug(e.toString());
                }
            }
        });
        readThread.start();
    }

    // This could have been a ring buffer.
    byte[] buffer = new byte[100];
    int bufferSize = 0;

    public static final int ZONE_LEFT_FRONT = 0;
    public static final int ZONE_LEFT_BACK = 1;
    public static final int ZONE_RIGHT_BACK = 2;
    public static final int ZONE_RIGHT_FRONT = 3;

    private final byte[] powerLevels2Byte = { RAW_POWER_LEVEL_0, RAW_POWER_LEVEL_U,
            RAW_POWER_LEVEL_1, RAW_POWER_LEVEL_2, RAW_POWER_LEVEL_3, RAW_POWER_LEVEL_4,
            RAW_POWER_LEVEL_5, RAW_POWER_LEVEL_6, RAW_POWER_LEVEL_7, RAW_POWER_LEVEL_8,
            RAW_POWER_LEVEL_9, RAW_POWER_LEVEL_P };

    private int getPowerLevelFromByte(byte data) {
        for (int i = 0; i < powerLevels2Byte.length; i++) {
            if (powerLevels2Byte[i] == data) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Turn the power card on or off, sent by keyboard
     *
     * @param on
     */
    public void setMainPower(boolean on) {
        // POWER_ON_COMMAND
        // C9 44 2C 03 C0 00 63 01
        byte[] packetData = { (byte) 0xC0, 0x00, 0x00 };
        if (on) {
            packetData[2] = 0x63;
        } else {
            packetData[2] = 0x64;
        }
        sendPacket(POWER_ON_COMMAND, packetData, true);
    }

    /**
     * Send command to power card in order to control power level on all zones.
     *
     * @param powerLevels
     *            What level 0 - 11 of each zone, see ZONE_*
     */
    public void setPowerLevel(int[] powerLevels) {
        // POWER_ON_COMMAND 50 00 01 01 01 01 00
        byte[] packetData = { 0x50, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 };
        for (int i = 0; i < 4; i++) {
            packetData[i + 2] = powerLevels2Byte[powerLevels[i]];
        }
        sendPacket(POWER_ON_COMMAND, packetData, true);
    }

    private synchronized void sendPacket(short command, byte[] packetData,
            boolean requireAck) {
        if (ack !=null) {
            logError("Concurrent use of lib not allowed!");
            throw new RuntimeException("Concurrent use of lib not allowed!");
        }
        byte[] packet = new byte[packetData.length + 5];
        packet[0] = (byte) 0xC9;
        packet[1] = (byte) (command >>> 8);
        packet[2] = (byte) (command & 0xFF);
        packet[3] = (byte) packetData.length;
        System.arraycopy(packetData, 0, packet, 4, packetData.length);
        byte checksum = calculateCheckSum(packet, packet.length);
        packet[packet.length - 1] = checksum;
        if (requireAck) {
            ack = checksum;
        }
        try {
            int retries = MAX_RETRIES;
            do {
                logDebug("Sending packet.");
                os.write(packet);
                this.wait(MAX_ACK_WAIT_TIME);
            } while ( (ack !=null) && (retries-- > 0) );
            if (ack != null) {
                logError("No ack received with checksum " + checksum);
                // Reset so that we can go on and send more commands
                ack = null;
            }
        } catch (IOException e) {
            logDebug(e.toString());
        } catch (InterruptedException e) {
            logError("Interrupted while waiting for ack");
        }
    }

    private void sendAckPacket(byte checksum) {
//        if (true) {
//            logDebug("Ack disabled");
//            return;
//        }
        logDebug("Sending ack packet:"
                + String.format("%02X ", checksum));
        byte[] packet = {PACKET_TYPE_ACK, checksum ,0};
        packet[2] = calculateCheckSum(packet, 3);
        try {
            os.write(packet);
        } catch (IOException e) {
            logDebug(e.toString());
        }
    }

    /**
     * Data protocol is a inverted UART with open collector where both Rx and Tx
     * share the same line. Communication settings is 9600, even parity, 1 stop
     * bit.
     *
     * The data is sent as packets of two types: PACKET_TYPE_COMMAND and
     * PACKET_TYPE_ACK. The format of the packets are described below.
     *
     * NOTE: The PACKET_TYPE_* Is not a unique byte in the stream, the checksum
     * might very well be one of these values (have been seen in the pot on
     * command).
     *
     * Both the power card and the keyboard can initiate communication. I guess
     * collisions are handled with the checksum ping-pong protocol.
     */

    /**
     * PACKET_TYPE_ACK
     *
     * This packet is sent as a response to PACKET_TYPE_COMMAND the format is:
     * 98 xx yy Where
     * xx is the checksum of the PACKET_TYPE_COMMAND (CC below)
     * yy is the checksum of this PACKET_TYPE_ACK
     */
    static final byte PACKET_TYPE_ACK = (byte) 0x98;

    /**
     * PACKET_TYPE_COMMAND
     *
     * This packet has the following format: C9 XX YY LL nn nn nn nn CC Where:
     * XX YY is the command, if XX is zero it seems like there are no ack sent
     *       XX could also be seen as a "from" address and YY the "to" address
     *       where 2C would be the power card and 44 and 47 the keyboard
     * nn is the pay load, see code below
     * LL is the remaining length of the packet not counting the checksum byte
     * CC is the checksum byte if this packet.
     */
    static final byte PACKET_TYPE_COMMAND = (byte) 0xC9;

    static final short POWER_ON_COMMAND = 0x442C; // From keyboard
                                                  // Seems like this is resent
                                                  // every ~10s. If not, the
                                                  // power card powers off

    static final short POWERED_ON_COMMAND = 0x2C44; // From power card

    static final short POWERED_ON_COMMAND_NO_ACK = 0x0044; // From power card,
                                                           // no ack is sent

    static final short POT_PRESENT_STATUS_NO_ACK = 0x0047; // From power card,
                                                           // no ack is sent

    static final short POT_PRESENT_STATUS = 0x2C47; // From power card
                                                    // when removing pot

    // Unknown packets. Sent after power cycle:
    // C9 24 2C 02 17 02 D6
    //
    // C9 2C 44 09 17 03 01 84 00 F4 5A EF 88 F0
    // C9 44 2C 03 73 02 00 D3
    // C9 2C 44 03 73 03 00 D2
    // C9 44 2C 02 15 02 B4

    private static final byte LF_DETECT_MASK = 0X01;
    private static final byte LB_DETECT_MASK = 0x04;
    private static final byte RB_DETECT_MASK = 0x10;
    private static final byte RF_DETECT_MASK = 0x40;

    private static final byte ZONE_HOT_MASK = 0x40;
    private static final byte POWER_ACTIVE_MASK = 0x01;

    private static final byte RAW_POWER_LEVEL_0 = 0x00;
    private static final byte RAW_POWER_LEVEL_U = 0x01;
    private static final byte RAW_POWER_LEVEL_1 = 0x02;
    private static final byte RAW_POWER_LEVEL_2 = 0x08;
    private static final byte RAW_POWER_LEVEL_3 = 0x0C;
    private static final byte RAW_POWER_LEVEL_4 = 0x0F;
    private static final byte RAW_POWER_LEVEL_5 = 0x11;
    private static final byte RAW_POWER_LEVEL_6 = 0x14;
    private static final byte RAW_POWER_LEVEL_7 = 0x15;
    private static final byte RAW_POWER_LEVEL_8 = 0x16;
    private static final byte RAW_POWER_LEVEL_9 = 0x17;
    private static final byte RAW_POWER_LEVEL_P = 0x18;

    public static final byte POWERSTATUS_OFF = 0x00;
    public static final byte POWERSTATUS_ON_IDLE = 0x01;
    public static final byte POWERSTATUS_ON_ACTIVE = 0x03;

    String commandString = "";
    String paramString = "";

    // A packet should not take longer than 40ms to receive
    // @ 9600 bps/s -> 1 start bit + 8 bit data + even parity + 1 stop bit
    // 9600 / 11 = 873 bytes/s. Largest packet seen is 14 bytes (?) which
    // then takes 16ms. But running over BT it seems to take longer, so lets increase it
    // a bit.
    private static long MAX_PACKET_TIME = 300;
    private long packetStartTs = 0;

    private void decodeData() {
        // Start patterns:
        // 0x9C packet with length at index 3 (minus checksum)
        // 0x98 packet with length = 3
        //
        // Note, this code is not correct since the "start pattern" can
        // exist elsewhere in the data stream. A more robust packet detection
        // would be to use some timing when the bytes are arrived (i.e. "old"
        // bytes are dropped). This seems to be used at least by the power card
        // which seems to drop packets when they are sent one byte a time with
        // some delay between.

        boolean packetFound = false;
        for (int i = 0; i < bufferSize; i++) {
            if ((buffer[i] == PACKET_TYPE_ACK)
                    || (buffer[i] == PACKET_TYPE_COMMAND)) {
                packetFound = true;
                if (i != 0) {
                    logDebug("Hmm, packet not at start?");
                    // remove crap data, packet should start at index 0
                    System.arraycopy(buffer, i, buffer, 0, bufferSize - i);
                    bufferSize -= i;
                }
                break;
            } else {
                System.out.println("Buffer 0x" + Integer.toHexString(buffer[i]));
            }
        }
        if (!packetFound) {
            logDebug("No packet found, clearing buffer");
            bufferSize = 0;
            packetStartTs = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (packetStartTs == 0) {
            packetStartTs = now;
        } else if ((now - packetStartTs) > MAX_PACKET_TIME) {
            logDebug("Packet time too long (" + (now - packetStartTs) +
                    "ms), reset buffer");
            bufferSize = 0;
            packetStartTs = 0;
            return;
        }

        int packetLen = 0;
        if (buffer[0] == PACKET_TYPE_COMMAND) {
            if (bufferSize < 6) {
                // Complete packet not yet received
                return;
            }
            packetLen = (buffer[3] & 0xFF) + 5;
            if (bufferSize < packetLen) {
                // Complete packet not yet received
                return;
            }

            // Complete package received
            logPacketData(getTs() + getHexString(buffer, 0, packetLen));

            byte checksum = calculateCheckSum(buffer, packetLen);
            if (buffer[packetLen - 1] != checksum) {
                logError("Wrong packet checksum!");
            } else {

                short command = (short) ((buffer[1] << 8) | (buffer[2] & 0xFF));
                commandString = String.format("%04X", command);
                paramString = "";

                boolean expectAck = buffer[1] == 0 ? false : true;

                // Send ack packets according to the role of this library
                // PASSIVE role does not send any ack's
                if (expectAck) {
                    if (command == POWER_ON_COMMAND) {
                        if (role.equals(Role.POWERCARD)) {
                            sendAckPacket(checksum);
                        }
                    } else if (role.equals(Role.KEYBOARD)) {
                        sendAckPacket(checksum);
                    }
                }

                switch (command) {
                case POWER_ON_COMMAND:
                    decodePowerOnCommand();
                    break;
                case POWERED_ON_COMMAND_NO_ACK:
                case POWERED_ON_COMMAND:
                    decodePoweredOnCommand();
                    break;
                case POT_PRESENT_STATUS_NO_ACK:
                case POT_PRESENT_STATUS:
                    decodePotPresentStatus();
                    break;
               default:
                    commandString += " ------------ - -- - UNKNOWN";
                    break;
                }
                logDebug(getTs() + "COMMAND cmd=" + commandString
                        + paramString);
            }
        } else if (buffer[0] == PACKET_TYPE_ACK) {
            packetLen = 3; // including checksum
            if (bufferSize < packetLen) {
                // Packet not yet fully received
                return;
            }

            logPacketData(getTs() + getHexString(buffer, 0, packetLen));
            logDebug(getTs() + "ACK of package with checksum = "
                    + String.format("%02X", buffer[1]));

            byte checksum = calculateCheckSum(buffer, packetLen);
            if (buffer[packetLen - 1] != checksum) {
                logError("Wrong packet checksum in ack packet!");
            } else {
                synchronized(this) {
                    if (ack != null) {
                        if (ack.equals(buffer[1])) {
                            ack = null;
                            this.notifyAll();
                            logDebug("Acked that we wait for received");
                        }
                    }
                }
            }
        } else {
            // As long as the scan for packet start remains above
            // this should never execute.
            throw new RuntimeException("Wierd code path");
        }

        // Throw away packet from buffer.
        packetStartTs = 0;
        System.arraycopy(buffer, packetLen, buffer, 0, bufferSize - packetLen);
        bufferSize -= packetLen;
        if (bufferSize != 0) {
            decodeData(); // Decode next packet
        }
    }

    private void decodePotPresentStatus() {
        commandString += "<-(POT_PRESENT_STATUS)";
        // C9 2C 47 04 54 03 55 3C 98
        // 0  1  2  3  4  5  6  7  8
        // Check so this really is a command that we know about
        if ((buffer[4] == (byte) 0x54) && (buffer[5] == (byte) 0x03)
                && (buffer[7] == (byte) 0x3C)) {

            boolean[] present = new boolean[4];
            paramString = "ST: ";
            byte presentMask = buffer[6];
            if ((presentMask & LF_DETECT_MASK) != 0) {
                paramString += "LF ";
                present[ZONE_LEFT_FRONT] = true;
            }
            if ((presentMask & LB_DETECT_MASK) != 0) {
                paramString += "LB ";
                present[ZONE_LEFT_BACK] = true;
            }
            if ((presentMask & RB_DETECT_MASK) != 0) {
                paramString += "RB ";
                present[ZONE_RIGHT_BACK] = true;
            }
            if ((presentMask & RF_DETECT_MASK) != 0) {
                paramString += "RF ";
                present[ZONE_RIGHT_FRONT] = true;
            }
            powerCardCallback.onPotPresent(present);
        } else {
            paramString = "Unknown POT_PRESENT_STATUS params!";
        }
    }

    private void decodePoweredOnCommand() {
        // Also seen, don't know what they mean:
        // C9 2C 44 03 73 03 00 D2
        // C9 2C 44 03 73 03 02 D0

        // power limit, no ack is sent
        // C9 00 44 07 18 03 00 00 11 18 FF 67
        // C9 00 44 07 18 03 11 18 11 18 FF 6E
        //
        // power limit:
        // C9 2C 44 07 18 03 00 00 11 18 FF 4B
        // | <- limit command?
        // |                 lf
        // |                    lb
        // |                       rb ==> limited to level 5!
        // |                          rf
        // LF Hot, all off:
        // C9 2C 44 08 15 03 00 40 00 00 00 04 FB

        // Back on U LF hot:
        // C9 2C 44 08 15 03 03 40 01 01 00 04 F8
        //
        //
        // C9 2C 44 08 15 03 03 00 00 01 00 04 B9
        // Idle power plate indication?
        // C9 2C 44 08 15 03 03 01 01 01 01 04 B8
        //                      | left front
        //                         | left back
        //                            | right back
        //                               | right front
        //
        // C9 2C 44 08 15 03 01 00 00 00 00 04 BA Off
        // C9 2C 44 08 15 03 00 00 00 00 00 04 BB On
        // 0 1 2 3 4 5 6
        // |-- 0x00 -> Off, 0x01 -> On
        commandString += "<-(POWRD)";
        if (buffer[4] == (byte) 0x15) {
            byte powerStatus = buffer[6];

            if (powerStatus == POWERSTATUS_OFF) {
                paramString = " OFF";
            } else if (powerStatus == POWERSTATUS_ON_IDLE) {
                paramString = " ON (idle)";
            } else if (powerStatus == POWERSTATUS_ON_ACTIVE) {
                paramString = " ON (active)";
            } else {
                paramString = " -------------------UNKNOWN powerStatus: "
                        + powerStatus;
                powerCardCallback.onUnknownData();
            }
            boolean[] powered = new boolean[4];
            boolean[] hot = new boolean[4];
            String[] zoneNames = {"LF", "LB", "RB", "RF"};
            for (int i = 0; i < powered.length; i++) {
                powered[i] = (buffer[i + 7] & POWER_ACTIVE_MASK) != 0;
                hot[i] = ((buffer[i + 7] & ZONE_HOT_MASK) != 0);
                paramString += " " + zoneNames[i] + ":"
                        + (powered[i] ? " On" : " Off")
                        + (hot[i] ? " Hot" : " Cold");
            }

            powerCardCallback.onPoweredOnCommand(powerStatus, powered,
                    hot);
        } else if (buffer[4] == (byte) 0x18) {
            paramString = " (PLIMIT)"; // Power limited
            paramString += " LF LB RB RF:" + getHexString(buffer, 6, 4);

            int[] levels = new int[4];
            for (int i = 0; i < levels.length; i++) {
                levels[i] = getPowerLevelFromByte(buffer[i + 6]);
            }
            powerCardCallback.onPowerLimitCommand(levels);
        } else if (buffer[4] == (byte) 0x73) {
            // Example of packages:
            // C9 2C 44 03 73 03 00 D2
            // C9 2C 44 03 73 03 02 D0
            paramString = " --------Unknown packet that has been seen before";
        } else {
            paramString = " -------------------UNKNOWN!:" +
                    String.format("%02X", buffer[4]);
            powerCardCallback.onUnknownData();
        }
    }

    private void decodePowerOnCommand() {
        commandString += "->(PWR)";
        if (buffer[4] == (byte) 0xC0) {
            // C9 44 2C 03 C0 00 63 01
            paramString += " MPWR";
            if (buffer[6] == 0x64) {
                paramString += " OFF";
                keyBoardCallback.onSetMainPowerCommand(false);
            } else if (buffer[6] == (byte) 0x63) {
                paramString += " ON";
                keyBoardCallback.onSetMainPowerCommand(true);
            } else {
                paramString += " --------------------UNKNOWN!!!-------";
                keyBoardCallback.onUnknownData();
            }
        } else if (buffer[4] == (byte) 0x50) {
            // zone power
            // 50 00 01 01 01 01 00
            //       | left front
            //          | left inner
            //             | right inner
            //                | right front
            paramString = " FL BL BR FR:" + getHexString(buffer, 6, 4);
            int[] powerLevels = new int[4];
            for (int i = 0; i < powerLevels.length; i++) {
                powerLevels[i] = getPowerLevelFromByte(buffer[i + 6]);
            }
            keyBoardCallback.onPowerOnCommand(powerLevels);
        } else {
            paramString = "          --- UNKNOWN ---";
            keyBoardCallback.onUnknownData();
        }
    }

    private byte calculateCheckSum(byte[] packet, int packetLenght) {
        byte checkSum = 0;
        for (int i = 0; i < packetLenght - 1; i++) {
            checkSum = (byte) (checkSum ^ packet[i]);
        }
        return checkSum;
    }

    private void logPacketData(String line) {
        //System.out.println(line);
    }

    private void logDebug(String line) {
        //System.out.println(line);
    }

    private void logError(String line) {
        System.err.println(line);
    }

    private String getTs() {
        return new Timestamp(System.currentTimeMillis()).toString() + " ";
    }

    private String getHexString(byte[] array, int off, int len) {
        StringBuffer data = new StringBuffer();
        for (int i = off; i < (len + off); i++) {
            data.append(String.format("%02X ", array[i] & 0xFF));
        }
        return data.toString();
    }
}
