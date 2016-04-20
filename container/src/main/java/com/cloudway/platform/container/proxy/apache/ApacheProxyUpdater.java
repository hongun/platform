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
import com.google.common.collect.ImmutableSet;

import com.cloudway.fp.data.Maybe;
import com.cloudway.fp.data.PMap;
import com.cloudway.fp.data.Seq;
import com.cloudway.fp.data.BooleanRef;
import com.cloudway.platform.container.proxy.HttpProxyUpdater;
import com.cloudway.platform.container.Container;
import com.cloudway.platform.container.proxy.ProxyMapping;
import static com.cloudway.fp.control.Predicates.*;

public enum ApacheProxyUpdater implements HttpProxyUpdater
{
    INSTANCE;

    private final ApacheDB containers = new ApacheDB("containers", true);
    private final ApacheDB mappings   = new ApacheDB("mappings");
    private final ApacheDB aliases    = new ApacheDB("aliases");
    private final ApacheDB idles      = new ApacheDB("idles");

    private static final Set<String> SUPPORTED_PROTOCOLS =
        ImmutableSet.of("http", "https", "ajp", "fcgi", "scgi", "ws", "wss");

    @Override
    public void addMappings(Container ac, Collection<ProxyMapping> map)
        throws IOException
    {
        addContainer(ac);

        mappings.write(d -> Seq.wrap(map)
            .filter(having(ProxyMapping::getProtocol, is(oneOf(SUPPORTED_PROTOCOLS))))
            .foldLeft(d, (m, pm) -> m.putIfAbsent(ac.getId() + pm.getFrontend(), pm.getBackend())));
    }

    @Override
    public void removeMappings(Container ac, Collection<ProxyMapping> map)
        throws IOException
    {
        mappings.write(d -> Seq.wrap(map)
            .filter(having(ProxyMapping::getProtocol, is(oneOf(SUPPORTED_PROTOCOLS))))
            .map(pm -> ac.getId() + pm.getFrontend())
            .foldLeft(d, PMap::remove));
    }

    private void removeAllMappings(Container ac)
        throws IOException
    {
        String id = ac.getId();
        mappings.write(d -> d.removeKeys(k -> {
            int i = k.indexOf('/');
            if (i != -1)
                k = k.substring(0, i);
            return k.equals(id);
        }));
    }

    private void addContainer(Container ac)
        throws IOException
    {
        containers.write(d ->
            d.merge(ac.getDomainName(), ac.getId(), (oldValue, value) ->
                oldValue.contains(value) ? oldValue : oldValue + "|" + value
            ));
    }

    private void removeContainer(Container ac)
        throws IOException
    {
        String fqdn = ac.getDomainName();
        String id = ac.getId();
        BooleanRef removed = new BooleanRef();

        containers.write(d -> d.computeIfPresent(fqdn, (key, value) -> {
            String newValue = Seq.of(value.split("\\|"))
                .filter(not(id))
                .collect(Collectors.joining("|"));
            if (newValue.isEmpty()) {
                removed.set(true);
                return Maybe.empty();
            } else {
                return Maybe.of(newValue);
            }
        }));

        // remove aliases if container is fully removed
        if (removed.get()) {
            aliases.write(d -> d.removeValues(fqdn));
        }
    }

    @Override
    public void addAlias(String name, String fqdn)
        throws IOException
    {
        aliases.write(d -> d.put(name, fqdn));
    }

    @Override
    public void removeAlias(String name)
        throws IOException
    {
        aliases.write(d -> d.remove(name));
    }

    @Override
    public void idle(Container ac)
        throws IOException
    {
        String time = LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME);
        idles.write(d -> d.put(ac.getId(), time));
    }

    @Override
    public boolean unidle(Container ac)
        throws IOException
    {
        BooleanRef result = new BooleanRef();
        idles.write(d -> {
            result.set(d.containsKey(ac.getId()));
            return d.remove(ac.getId());
        });
        return result.get();
    }

    @Override
    public boolean isIdle(Container ac) {
        try {
            return idles.read(d -> d.containsKey(ac.getId()));
        } catch (IOException ex) {
            return false;
        }
    }

    @Override
    public void purge(Container ac) throws IOException {
        removeContainer(ac);
        removeAllMappings(ac);
        unidle(ac);
    }
}
