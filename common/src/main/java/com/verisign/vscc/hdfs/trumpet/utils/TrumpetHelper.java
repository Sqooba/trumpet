package com.verisign.vscc.hdfs.trumpet.utils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.verisign.vscc.hdfs.trumpet.kafka.KafkaUtils;
import kafka.message.Message;

import java.io.IOException;
import java.util.Map;


public class TrumpetHelper {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static Map<String, Object> toMap(byte[] payload) throws IOException {
        final Map<String, Object> o = mapper.readValue(payload, Map.class);
        return o;
    }

    public static Map<String, Object> toMap(Message message) throws IOException {
        return toMap(KafkaUtils.toByteArray(message));
    }

}
