package org.apache.zookeeper.server.util;

/**
 *
 * This utils is just used to color the string and help debug
 *
 * @author alstonwilliams
 *
 * */
public class RedStringUtil {

    public static String redString(String originalString){
        return new StringBuffer((char)27 + "[31m").append(originalString).append((char)27 + "[0m").toString();
    }

}
