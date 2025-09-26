package org.kazamistudio.corePlugin.util;

public class ColorUtil {
    // ANSI color codes
    public static final String RESET = "\u001B[0m";
    public static final String BLACK = "\u001B[30m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String PURPLE = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    // Chuyển "&" code sang ANSI color console
    public static String translateColors(String message) {
        return message
                .replace("&0", BLACK)
                .replace("&1", BLUE)
                .replace("&2", GREEN)
                .replace("&3", CYAN)
                .replace("&4", RED)
                .replace("&5", PURPLE)
                .replace("&6", YELLOW)
                .replace("&7", WHITE)
                .replace("&r", RESET);
    }
}
