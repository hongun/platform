/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.platform.container.shell;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.Stream;

import com.cloudway.platform.common.os.Config;
import com.cloudway.platform.common.os.Etc;
import com.cloudway.platform.container.Container;

/**
 * Entry point for application control commands.
 */
public abstract class Control
{
    protected static boolean verbose;

    public static void main(String args[]) {
        Control control;
        if (Etc.getuid() == 0) {
            control = new PrivilegedControl();
        } else {
            control = new UserControl();
        }

        int i = 0;
        if (args.length > 0 && args[0].equals("-v")) {
            verbose = true;
            i++;
        }

        Method action = null;
        Throwable failure = null;

        if (i < args.length) {
            try {
                String command = args[i].replaceAll("-", "_");
                action = control.getClass().getMethod(command, String[].class);
            } catch (Exception ex) {
                // fallthrough to report error
            }
        }

        if (action == null) {
            System.err.println("Invalid command. Use \"cwctl help\" for more information");
            System.exit(1);
        }

        try {
            Object[] arguments = new Object[1];
            arguments[0] = Arrays.copyOfRange(args, i+1, args.length);
            action.invoke(control, arguments);
        } catch (InvocationTargetException ex) {
            failure = ex.getCause();
        } catch (Exception ex) {
            failure = ex;
        }

        if (failure != null) {
            System.err.println("Command failure: " + failure);
            if (verbose || failure.getMessage() == null)
                failure.printStackTrace();
            System.exit(2);
        }
    }

    @Command("Show this help message")
    @SuppressWarnings("unused")
    public void help(String[] args) {
        System.err.println("Usage: cwctl COMMAND [ARGS...]");
        System.err.println();
        System.err.println("COMMANDS:");
        System.err.println();
        Stream.of(this.getClass().getMethods())
            .sorted(Comparator.comparing(Method::getName))
            .forEach(m -> {
                Command description = m.getAnnotation(Command.class);
                if (description != null) {
                    System.err.printf("  %-12s%s%n", m.getName(), description.value());
                }
            });
        System.err.println();
    }

    protected void install(Container container, String source, String repo)
        throws IOException
    {
        Path source_path;
        if (source.indexOf('/') != -1) {
            source_path = Paths.get(source).toAbsolutePath().normalize();
        } else {
            source_path = Config.VAR_DIR.resolve(".plugins", source);
        }

        if (Files.exists(source_path)) {
            container.install(source_path, repo);
        } else {
            throw new FileNotFoundException(source + ": No such file or directory");
        }
    }
}
