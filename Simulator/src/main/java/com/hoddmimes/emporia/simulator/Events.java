package com.hoddmimes.emporia.simulator;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Prints the simulator's events, one line each: time, event, what. */
final class Events {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private Events() {}

    static synchronized void print(String pEvent, String pText) {
        System.out.printf("%s %-16s %s%n", LocalDateTime.now().format(TIME), pEvent, pText);
    }
}
