package io.openschema.mma.utils;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.lang.NumberFormatException;
import java.util.concurrent.Executor;
import java.util.StringTokenizer;

public class DnsPing {

    private static final String TAG = "DNS Test";
    private static final int timeout = 5000;
    private static final int dnsPort = 53;

    private static String[] testDomains = {"qkieASX3S9.com", "x6e077uejM.com", "zr50V1DAXx.com", "3GNnaZUwE2.com", "K4255rzaKc.com"};
    private static String[] testDNSs = {"8.8.8.8", "9.9.9.9", "1.1.1.1", "185.228.168.9", "76.76.19.19"};
    private static byte[][] testDomainsRequests;

    private final Executor executor;

    public DnsPing(Executor executor){

        this.executor = executor;
        testDomainsRequests = new byte[testDomains.length][0];
        for(int i = 0; i < testDomains.length; i++) {
            try {
                testDomainsRequests[i] = getDnsRequest(testDomains[i]);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void dnsTest(String[] dnsServers) {
        executor.execute(() -> {
            for(int i = 0; i < testDNSs.length; i++){
                requestAllDomains(testDNSs[i]);
            }

            for(int i = 0; i < dnsServers.length; i++){
                requestAllDomains(dnsServers[i]);
            }
        });
    }

    private static QosInfo requestAllDomains(String dnsServer) {
        //TODO: Implement Retries

        float[] individualValues = new float[testDomains.length];
        float result = 0f;
        int failures = 0;

        for (int i = 0; i < testDomains.length; i++) {

            try {
                individualValues[i] = requestDomain(dnsServer, testDomainsRequests[i]);
                Log.d(TAG, "DNS RTT Result " + dnsServer +  " on " + testDomains[i] + ": " + Float.toString(individualValues[i]));
                result += individualValues[i];
            } catch (IOException e) {
                failures++;
                Log.d(TAG, "DNS RTT Error " + dnsServer +  ": " + e);
            }
        }

        QosInfo qosInfo = new QosInfo(dnsServer, individualValues, result/testDomains.length, failures);
        Log.d(TAG, "DNS RTT Average Result " + qosInfo.getDnsServer() +  ": " + Float.toString(qosInfo.getMean()));
        Log.d(TAG, "DNS RTT variance " + qosInfo.getDnsServer() + ": " + Float.toString(qosInfo.getVariance()));
        Log.d(TAG, "DNS RTT failures " + qosInfo.getDnsServer() + ": " + Integer.toString(qosInfo.getTotalFailure()));
        return qosInfo;
    }



    private static float requestDomain(String dnsServer, byte[] requestQuestion) throws IOException {
        //Request
        DatagramPacket requestPacket;
        requestPacket = new DatagramPacket(requestQuestion, requestQuestion.length, InetAddress.getByAddress(getServer(dnsServer)), dnsPort);

        //Response
        DatagramPacket responsePacket;
        byte [] byteArray = new byte[1024];
        responsePacket = new DatagramPacket(byteArray, byteArray.length);

        //Operation
        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(timeout);

        long startTime = System.nanoTime();
        socket.send(requestPacket);
        socket.receive(responsePacket);
        long endTime = System.nanoTime();
        socket.close();

        long queryDuration = (endTime - startTime);  //divide by 1000000 to get milliseconds.

        return queryDuration/1000000;
    }

    private final static int parseNumericAddress(String ipaddr) {

        //  Check if the string is valid

        if ( ipaddr == null || ipaddr.length() < 7 || ipaddr.length() > 15)
            return 0;

        //  Check the address string, should be n.n.n.n format

        StringTokenizer token = new StringTokenizer(ipaddr,".");
        if ( token.countTokens() != 4)
            return 0;

        int ipInt = 0;

        while ( token.hasMoreTokens()) {

            //  Get the current token and convert to an integer value

            String ipNum = token.nextToken();

            try {

                //  Validate the current address part

                int ipVal = Integer.valueOf(ipNum).intValue();
                if ( ipVal < 0 || ipVal > 255)
                    return 0;

                //  Add to the integer address

                ipInt = (ipInt << 8) + ipVal;
            }
            catch (NumberFormatException ex) {
                return 0;
            }
        }

        //  Return the integer address

        return ipInt;
    }

    private static byte[] getServer(String address) {

        int ipInt = parseNumericAddress(address);
        if ( ipInt == 0)
            return null;

        byte [] server = new byte[4];

        server[3] = (byte) (ipInt & 0xFF);
        server[2] = (byte) ((ipInt >> 8) & 0xFF);
        server[1] = (byte) ((ipInt >> 16) & 0xFF);
        server[0] = (byte) ((ipInt >> 24) & 0xFF);

        return server;
    }

    private static byte[] getDnsRequest(String domain) throws IOException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        // *** Build a DNS Request Frame ****

        // Identifier: A 16-bit identification field generated by the device that creates the DNS query.
        // It is copied by the server into the response, so it can be used by that device to match that
        // query to the corresponding reply received from a DNS server. This is used in a manner similar
        // to how the Identifier field is used in many of the ICMP message types.
        dos.writeShort(0x0000);

        // Write Query Flags
        dos.writeShort(0x0100);

        // Question Count: Specifies the number of questions in the Question section of the message.
        dos.writeShort(0x0001);

        // Answer Record Count: Specifies the number of resource records in the Answer section of the message.
        dos.writeShort(0x0000);

        // Authority Record Count: Specifies the number of resource records in the Authority section of
        // the message. (“NS” stands for “name server”)
        dos.writeShort(0x0000);

        // Additional Record Count: Specifies the number of resource records in the Additional section of the message.
        dos.writeShort(0x0000);

        String[] domainParts = domain.split("\\.");
        System.out.println(domain + " has " + domainParts.length + " parts");

        for (int i = 0; i<domainParts.length; i++) {
            System.out.println("Writing: " + domainParts[i]);
            byte[] domainBytes = domainParts[i].getBytes("UTF-8");
            dos.writeByte(domainBytes.length);
            dos.write(domainBytes);
        }

        // No more parts
        dos.writeByte(0x00);

        // Type 0x01 = A (Host Request)
        dos.writeShort(0x0001);

        // Class 0x01 = IN
        dos.writeShort(0x0001);

        byte[] dnsFrame = baos.toByteArray();

        return dnsFrame;
    }
}
