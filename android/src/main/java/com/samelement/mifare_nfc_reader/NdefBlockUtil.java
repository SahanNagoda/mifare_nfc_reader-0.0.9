package com.samelement.mifare_nfc_reader;

import java.util.List;

public class NdefBlockUtil {

    public static int getBlockIndex(List<String> block) {
        System.out.println("cari block index "+ block);
        // 0. Record Header
        int recordHeaderInt = Integer.parseInt(block.get(0), 16);
        String bin = Integer.toBinaryString(256 + recordHeaderInt).substring(1);

        // Record Header
        boolean messageBegin = bin.charAt(0) == '1';
        boolean messageEnd = bin.charAt(1) == '1';
        boolean chunkFlag = bin.charAt(2) == '1';
        boolean shortRecord = bin.charAt(3) == '1';
        boolean idLength = bin.charAt(4) == '1';
        int typeNameFormat = Integer.parseInt(bin.substring(5), 2);

        int typeLength = Integer.parseInt(block.get(1), 16);

        int payloadLength = 0;
        int firstIndex = 3;
        if (shortRecord) {
            payloadLength = Integer.parseInt(block.get(2), 16);
        } else {
            firstIndex = 6;
            payloadLength = Integer.parseInt(block.get(2) + block.get(3) + block.get(4) + block.get(5), 16);
        }

        int payloadIdLength = 0;
        if (idLength) {
            payloadIdLength = 1;
        }

        return firstIndex + typeLength + payloadLength + payloadIdLength;
    }
}
