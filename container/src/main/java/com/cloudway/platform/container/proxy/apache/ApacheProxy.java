/**
 * Cloudway Platform
 * Copyright (c) 2012-2013 Cloudway Technology, Inc.
 * All rights reserved.
 */

package com.cloudway.platform.container.proxy.apache;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.google.common.collect.ImmutableSet;

import com.cloudway.platform.container.ApplicationContainer;
import com.cloudway.platform.container.proxy.HttpProxy;
import com.cloudway.platform.container.proxy.ProxyMapping;

public enum ApacheProxy implements HttpProxy
{
    INSTANCE;

    private final ApacheDB containers = new ApacheDB("containers", true);
    private final ApacheDB mappings   = new ApacheDB("mappings");
    private final ApacheDB aliases    = new ApacheDB("aliases");
    private final ApacheDB idles      = new ApacheDB("idles");

    private static final Set<String> SUPPORTED_PROTOCOLS = ImmutableSet.of(
        "http", "https", "ajp", "fcgi", "scgi", "ws", "wss");

    @Override
    public void addMappings(ApplicationContainer ac, Collection<ProxyMapping> map)
        throws IOException
    {
        addContainer(ac);

        mappings.writting(d -> map.forEach(pm -> {
            if (SUPPORTED_PROTOCOLS.contains(pm.getProtocol())) {
                d.putIfAbsent(ac.getId() + pm.getFrontend(), pm.getBackend());
            }
        }));
    }

    @Override
    public void removeMappings(ApplicationContainer ac, Collection<ProxyMapping> map)
        throws IOException
    {
        mappings.writting(d -> map.stream()
            .filter(x -> SUPPORTED_PROTOCOLS.contains(x.getProtocol()))
            .map(pm -> ac.getId() + pm.getFrontend())
            .sorted()
            .distinct()
            .forEach(d::remove));
    }

    private void removeMappings(ApplicationContainer ac)
        throws IOException
    {
        String id = ac.getId();
        mappings.writting(d -> d.keySet().removeIf(k -> {
            int i = k.indexOf('/');
            if (i != -1)
                k = k.substring(0, i);
            return k.equals(id);
        }));
    }

    private void addContainer(ApplicationContainer ac)
        throws IOException
    {
        containers.writting(d ->
            d.merge(ac.getDomainName(), ac.getId(), (oldValue, value) ->
                oldValue.contains(value) ? oldValue : oldValue + "|" + value
            ));
    }

    private void removeContainer(ApplicationContainer ac)
        throws IOException
    {
        String fqdn = ac.getDomainName();
        String id = ac.getId();
        boolean removed[] = new boolean[1];

        containers.writting(d -> {
            String value = d.get(fqdn);
            if (value != null && value.contains(id)) {
                String newValue = Stream.of(value.split("\\|"))
                    .filter(s -> !s.equals(id))
                    .collect(Collectors.joining("|"));
                if (newValue.isEmpty()) {
                    d.remove(fqdn);
                    removed[0] = true;
                } else {
                    d.put(fqdn, newValue);
                }
            }
        });

        // remove aliases if container is fully removed
        if (removed[0]) {
            aliases.writting(d -> d.values().remove(fqdn));
        }
    }

    @Override
    public void addAlias(String name, String fqdn)
        throws IOException
    {
        aliases.writting(d -> d.put(name, fqdn));
    }

    @Override
    public void removeAlias(String name)
        throws IOException
    {
        aliases.writting(d -> d.remove(name));
    }

    @Override
    public void idle(ApplicationContainer ac)
        throws IOException
    {
        String time = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        idles.writting(d -> d.put(ac.getId(), time));
    }

    @Override
    public boolean unidle(ApplicationContainer ac)
        throws IOException
    {
        boolean cookie[] = new boolean[1];
        idles.writting(d -> cookie[0] = d.remove(ac.getId()) != null);
        return cookie[0];
    }

    @Override
    public boolean isIdle(ApplicationContainer ac) {
        try {
            boolean cookie[] = new boolean[1];
            idles.reading(d -> cookie[0] = d.containsKey(ac.getId()));
            return cookie[0];
        } catch (IOException ex) {
            return false;
        }
    }

    @Override
    public void purge(ApplicationContainer ac) throws IOException {
        removeContainer(ac);
        removeMappings(ac);
        unidle(ac);
    }
}
