package com.samelement.mifare_nfc_reader;

import java.util.List;

public class NdefBlock {
    private final boolean messageBegin;
    private final boolean messageEnd;
    private final boolean chunkFlag;
    private final boolean shortRecord;
    private final boolean idLength;
    private final int typeNameFormat;

    private final int typeLength;
    private final int payloadLength;
    private final int payloadIdLength;

    private final String typeField;
    private int ianaLangLength = 0;
    private String language = "";
    private String payload = "";

    public boolean isMessageBegin() {
        return messageBegin;
    }

    public boolean isMessageEnd() {
        return messageEnd;
    }

    public String getPayload() {
        return payload;
    }

    public boolean isEmptyRecord() {
        return typeNameFormat == 0;
    }

    public NdefBlock(List<String> block) {
        System.out.println("BLOCK INI " + block);

        int recordHeaderInt = Integer.parseInt(block.get(0), 16);
        String bin = Integer.toBinaryString(256 + recordHeaderInt).substring(1);

        // Record Header
        messageBegin = bin.charAt(0) == '1';
        messageEnd = bin.charAt(1) == '1';
        chunkFlag = bin.charAt(2) == '1';
        shortRecord = bin.charAt(3) == '1';
        idLength = bin.charAt(4) == '1';
        typeNameFormat = Integer.parseInt(bin.substring(5), 2);

        typeLength = Integer.parseInt(block.get(1), 16);

        int firstIndex = 3;
        if (shortRecord) {
            payloadLength = Integer.parseInt(block.get(2), 16);
        } else {
            firstIndex = 6;
            payloadLength = Integer.parseInt(block.get(2) + block.get(3) + block.get(4) + block.get(5), 16);
        }

        if (idLength) {
            payloadIdLength = 1;
        } else {
            payloadIdLength = 0;
        }

        // current index = 3
        StringBuilder typeFieldBuilder = new StringBuilder();
        for (int i = firstIndex; i < firstIndex + typeLength; i++) {
            typeFieldBuilder.append((char) Integer.parseInt(block.get(i), 16));
        }
        typeField = typeFieldBuilder.toString();
        System.out.println("type : " + typeField);

        // Status byte
        int index = firstIndex + typeLength + payloadIdLength;
        StringBuilder payloadBuilder = new StringBuilder();
        for (int i = index; i < index + payloadLength; i++) {
            if (typeNameFormat == 2) {
                // mime
                payloadBuilder.append((char) Integer.parseInt(block.get(i), 16));
            } else if (i == index) {
                int statusByteInt = Integer.parseInt(block.get(index), 16);
                String binStatusByte = Integer.toBinaryString(256 + statusByteInt).substring(1);
                String binIanaLang = binStatusByte.substring(2);
                ianaLangLength = Integer.parseInt(binIanaLang, 2);
            } else if (i == index + 1) {
                // Language
                StringBuilder languageBuilder = new StringBuilder();
                for (int j = i; j < i + ianaLangLength; j++) {
                    languageBuilder.append((char) Integer.parseInt(block.get(j), 16));
                }
                language = languageBuilder.toString();

                i = i + ianaLangLength - 1;
            } else {
                payloadBuilder.append((char) Integer.parseInt(block.get(i), 16));
            }

        }

        payload = payloadBuilder.toString();
    }
}
