package com.samelement.mifare_nfc_reader;

import java.util.ArrayList;
import java.util.List;

public class HexUtils {
    /**
     * Converts the HEX string to byte array.
     *
     * @param hexString the HEX string.
     * @return the byte array.
     */
    public static byte[] toByteArray(String hexString) {

        int hexStringLength = hexString.length();
        byte[] byteArray = null;
        int count = 0;
        char c;
        int i;

        // Count number of hex characters
        for (i = 0; i < hexStringLength; i++) {

            c = hexString.charAt(i);
            if (c >= '0' && c <= '9' || c >= 'A' && c <= 'F' || c >= 'a'
                    && c <= 'f') {
                count++;
            }
        }

        byteArray = new byte[(count + 1) / 2];
        boolean first = true;
        int len = 0;
        int value;
        for (i = 0; i < hexStringLength; i++) {

            c = hexString.charAt(i);
            if (c >= '0' && c <= '9') {
                value = c - '0';
            } else if (c >= 'A' && c <= 'F') {
                value = c - 'A' + 10;
            } else if (c >= 'a' && c <= 'f') {
                value = c - 'a' + 10;
            } else {
                value = -1;
            }

            if (value >= 0) {

                if (first) {

                    byteArray[len] = (byte) (value << 4);

                } else {

                    byteArray[len] |= value;
                    len++;
                }

                first = !first;
            }
        }

        return byteArray;
    }

    /**
     * Converts the integer to HEX string.
     *
     * @param i the integer.
     * @return the HEX string.
     */
    public static String toHexString(int i) {

        String hexString = Integer.toHexString(i);
        if (hexString.length() % 2 != 0) {
            hexString = "0" + hexString;
        }

        return hexString.toUpperCase();
    }

    /**
     * Converts the byte array to HEX string.
     *
     * @param buffer the buffer.
     * @return the HEX string.
     */
    public static String toHexString(byte[] buffer) {

        String bufferString = "";

        for (int i = 0; i < buffer.length; i++) {

            String hexChar = Integer.toHexString(buffer[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }

            bufferString += hexChar.toUpperCase() + " ";
        }

        return bufferString;
    }

    public static void logBuffer(byte[] buffer, int bufferLength) {

        String bufferString = "";

        for (int i = 0; i < bufferLength; i++) {

            String hexChar = Integer.toHexString(buffer[i] & 0xFF);
            if (hexChar.length() == 1) {
                hexChar = "0" + hexChar;
            }

            if (i % 16 == 0) {

                if (bufferString != "") {

                    System.out.println("16 byte print " + bufferString);
                    bufferString = "";
                }
            }

            bufferString += hexChar.toUpperCase() + " ";
        }

        if (bufferString != "") {
            System.out.println("sisa byte print " + bufferString);
        }
    }

    public static int toHexDecimal(String hex) {
        return Integer.parseInt(hex, 16);
    }

    public static String asciiToHex(String ascii) {
        char[] ch = ascii.toCharArray();

        StringBuilder builder = new StringBuilder();

        for (char c : ch) {
            int i = (int) c;
            builder.append(Integer.toHexString(i).toUpperCase());
        }

        return builder.toString();
    }

    public static String binaryStrToHex(String binaryStr) {
        int decimal = Integer.parseInt(binaryStr, 2);
        String hexStr = Integer.toString(decimal, 16);
        return hexStr.toUpperCase();
    }

    public static String decimalToFourBytesHex(int decimal) {
        StringBuilder hexString = new StringBuilder(Integer.toHexString(decimal));
        while (hexString.toString().length() != 8) {
            hexString.insert(0, "0");
        }

        return hexString.toString();
    }

    public static String decimalToTwoBytesHex(int decimal) {
        StringBuilder hexString = new StringBuilder(Integer.toHexString(decimal));
        while (hexString.toString().length() != 4) {
            hexString.insert(0, "0");
        }

        return hexString.toString();
    }

    public static List<String> usingSplitMethod(String text, int n) {
        List<String> parts = new ArrayList<>();
        int len = text.length();
        for (int i = 0; i < len; i += n) {
            parts.add(text.substring(i, Math.min(len, i + n)));
        }
        return parts;
    }


    public static String paddingTo16Bytes(String data) {
        StringBuilder hexString = new StringBuilder(data);
        while (hexString.toString().length() != 32) {
            hexString.append("00");
        }

        return hexString.toString();
    }
}
