/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.resourceresolver.impl.mapping;

import org.apache.commons.collections4.map.LRUMap;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.QuerySyntaxException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.resource.observation.ExternalResourceChangeListener;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChangeListener;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.apache.sling.resourceresolver.impl.mapping.MapConfigurationProvider.VanityPathConfig;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

public class MapEntries implements
    MapEntriesHandler,
    ResourceChangeListener,
    ExternalResourceChangeListener {

    private static final String JCR_CONTENT = "jcr:content";

    private static final String JCR_CONTENT_PREFIX = "jcr:content/";

    private static final String JCR_CONTENT_SUFFIX = "/jcr:content";

    private static final String PROP_REG_EXP = "sling:match";

    public static final String PROP_REDIRECT_EXTERNAL = "sling:redirect";

    public static final String PROP_REDIRECT_EXTERNAL_STATUS = "sling:status";

    public static final String PROP_REDIRECT_EXTERNAL_REDIRECT_STATUS = "sling:redirectStatus";

    public static final String PROP_VANITY_PATH = "sling:vanityPath";

    public static final String PROP_VANITY_ORDER = "sling:vanityOrder";

    private static final int VANITY_BLOOM_FILTER_MAX_ENTRIES = 10000000;

    /** Key for the global list. */
    private static final String GLOBAL_LIST_KEY = "*";

    public static final String DEFAULT_MAP_ROOT = "/etc/map";

    public static final int DEFAULT_DEFAULT_VANITY_PATH_REDIRECT_STATUS = HttpServletResponse.SC_FOUND;

    @SuppressWarnings("java:S1075") // Repository path
    private static final String JCR_SYSTEM_PATH = "/jcr:system";

    private static final String JCR_SYSTEM_PREFIX = JCR_SYSTEM_PATH + '/';

    static final String ALIAS_BASE_QUERY_DEFAULT = "SELECT sling:alias FROM nt:base AS page";

    static final String ANY_SCHEME_HOST = "[^/]+/[^/]+";

    /** default log */
    private final Logger log = LoggerFactory.getLogger(MapEntries.class);

    private volatile MapConfigurationProvider factory;

    private volatile ResourceResolver resolver;

    private volatile EventAdmin eventAdmin;

    private Optional<ResourceResolverMetrics> metrics;

    private volatile ServiceRegistration<ResourceChangeListener> registration;

    private Map<String, List<MapEntry>> resolveMapsMap;

    // Temporary cache for use while doing async vanity path query
    private Map<String, List<MapEntry>> temporaryResolveMapsMap;
    private List<Map.Entry<String, ResourceChange.ChangeType>> resourceChangeQueue;
    private AtomicLong temporaryResolveMapsMapHits = new AtomicLong();
    private AtomicLong temporaryResolveMapsMapMisses = new AtomicLong();

    private Collection<MapEntry> mapMaps;

    private Map <String,List <String>> vanityTargets;

    private Map<String, Map<String, String>> aliasMap;

    private final ReentrantLock initializing = new ReentrantLock();

    private final AtomicLong vanityCounter;
    private final AtomicLong vanityPathLookups;
    private final AtomicLong vanityPathBloomNegative;
    private final AtomicLong vanityPathBloomFalsePositive;

    private byte[] vanityBloomFilter;

    private AtomicBoolean vanityPathsProcessed = new AtomicBoolean(false);

    private final StringInterpolationProvider stringInterpolationProvider;

    private final boolean useOptimizeAliasResolution;

    public MapEntries(final MapConfigurationProvider factory, 
            final BundleContext bundleContext, 
            final EventAdmin eventAdmin, 
            final StringInterpolationProvider stringInterpolationProvider, 
            final Optional<ResourceResolverMetrics> metrics) 
                    throws LoginException, IOException {

        this.resolver = factory.getServiceResourceResolver(factory.getServiceUserAuthenticationInfo("mapping"));
        this.factory = factory;
        this.eventAdmin = eventAdmin;

        this.resolveMapsMap = Collections.singletonMap(GLOBAL_LIST_KEY, Collections.emptyList());
        this.mapMaps = Collections.<MapEntry> emptyList();
        this.vanityTargets = Collections.<String,List <String>>emptyMap();
        this.aliasMap = Collections.<String, Map<String, String>>emptyMap();
        this.stringInterpolationProvider = stringInterpolationProvider;

        this.useOptimizeAliasResolution = doInit();

        this.registration = registerResourceChangeListener(bundleContext);

        this.vanityCounter = new AtomicLong(0);
        this.vanityPathLookups = new AtomicLong(0);
        this.vanityPathBloomNegative = new AtomicLong(0);
        this.vanityPathBloomFalsePositive = new AtomicLong(0);
        initializeVanityPaths();

        this.metrics = metrics;
        if (metrics.isPresent()) {
            this.metrics.get().setNumberOfVanityPathsSupplier(vanityCounter::get);
            this.metrics.get().setNumberOfVanityPathLookupsSupplier(vanityPathLookups::get);
            this.metrics.get().setNumberOfVanityPathBloomNegativeSupplier(vanityPathBloomNegative::get);
            this.metrics.get().setNumberOfVanityPathBloomFalsePositiveSupplier(vanityPathBloomFalsePositive::get);
            this.metrics.get().setNumberOfAliasesSupplier(() -> (long) aliasMap.size());
        }
    }

    private ServiceRegistration<ResourceChangeListener> registerResourceChangeListener(final BundleContext bundleContext) {
        final Dictionary<String, Object> props = new Hashtable<>(); // NOSONAR - required by OSGi APIs
        final String[] paths = new String[factory.getObservationPaths().length];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = factory.getObservationPaths()[i].getPath();
        }
        props.put(ResourceChangeListener.PATHS, paths);
        props.put(Constants.SERVICE_DESCRIPTION, "Apache Sling Map Entries Observation");
        props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
        log.info("Registering for {}", Arrays.toString(factory.getObservationPaths()));

        this.resourceChangeQueue = Collections.synchronizedList(new LinkedList<>());
        return bundleContext.registerService(ResourceChangeListener.class, this, props);
    }

    /**
     * Actual initializer. Guards itself against concurrent use by using a
     * ReentrantLock. Does nothing if the resource resolver has already been
     * null-ed.
     */
    protected boolean doInit() {

        this.initializing.lock();
        try {
            final ResourceResolver resolver = this.resolver;
            final MapConfigurationProvider factory = this.factory;
            if (resolver == null || factory == null) {
                return this.factory.isOptimizeAliasResolutionEnabled();
            }

            boolean isOptimizeAliasResolutionEnabled = this.factory.isOptimizeAliasResolutionEnabled();

            //optimization made in SLING-2521
            if (isOptimizeAliasResolutionEnabled) {
                try {
                    final Map<String, Map<String, String>> loadedMap = this.loadAliases(resolver);
                    this.aliasMap = loadedMap;
    
                } catch (final Exception e) {

                    logDisableAliasOptimization(e);

                    // disable optimize alias resolution
                    isOptimizeAliasResolutionEnabled = false;
                }
            }

            this.resolveMapsMap = new ConcurrentHashMap<>();

            doUpdateConfiguration();

            sendChangeEvent();

            return isOptimizeAliasResolutionEnabled;

        } finally {

            this.initializing.unlock();

        }
    }

    /**
     * Actual vanity paths initializer. Guards itself against concurrent use by
     * using a ReentrantLock. Does nothing if the resource resolver has already
     * been null-ed.
     *
     * @throws IOException
     */
    protected void initializeVanityPaths() throws IOException {
        this.initializing.lock();
        try {
            if (this.factory.isVanityPathEnabled()) {
                this.vanityBloomFilter = createVanityBloomFilter();
                VanityPathInitializer vpi = new VanityPathInitializer(this.factory);
                if (this.factory.isVanityPathCacheInitInBackground()) {
                    this.log.debug("bg init starting");
                    Thread vpinit = new Thread(vpi, "VanityPathInitializer");
                    vpinit.start();
                } else {
                    vpi.run();
                }
            }
        } finally {
            this.initializing.unlock();
        }
    }

    private class VanityPathInitializer implements Runnable {

        private int SIZELIMIT = 10000;

        private MapConfigurationProvider factory;

        public VanityPathInitializer(MapConfigurationProvider factory) {
            this.factory = factory;
        }

        @Override
        public void run() {
            temporaryResolveMapsMap = Collections.synchronizedMap(new LRUMap<>(SIZELIMIT));
            execute();
        }

        private void drainQueue(List<Map.Entry<String, ResourceChange.ChangeType>> queue) {
            final AtomicBoolean resolverRefreshed = new AtomicBoolean(false);

            // send the change event only once
            boolean sendEvent = false;

            // the config needs to be reloaded only once
            final AtomicBoolean hasReloadedConfig = new AtomicBoolean(false);

            while (!queue.isEmpty()) {
                Map.Entry<String, ResourceChange.ChangeType> entry = queue.remove(0);
                final ResourceChange.ChangeType type = entry.getValue();
                final String path = entry.getKey();

                log.trace("drain type={}, path={}", type, path);
                boolean changed = handleResourceChange(type, path, resolverRefreshed, hasReloadedConfig);

                if (changed) {
                    sendEvent = true;
                }
            }

            if (sendEvent) {
                sendChangeEvent();
            }
        }

        private void execute() {
            try (ResourceResolver resolver = factory
                    .getServiceResourceResolver(factory.getServiceUserAuthenticationInfo("mapping"))) {

                long initStart = System.nanoTime();
                log.debug("vanity path initialization - start");

                vanityTargets = loadVanityPaths(resolver);

                // process pending event
                drainQueue(resourceChangeQueue);

                vanityPathsProcessed.set(true);

                // drain once more in case more events have arrived
                drainQueue(resourceChangeQueue);

                long initElapsed = System.nanoTime() - initStart;
                log.info("vanity path initialization - end, elapsed {}ms", TimeUnit.NANOSECONDS.toMillis(initElapsed));
            } catch (LoginException ex) {
                log.error("Vanity path init failed", ex);
            } finally {
                log.debug("dropping temporary resolver map - {}/{} entries, {} hits, {} misses", temporaryResolveMapsMap.size(),
                        SIZELIMIT, temporaryResolveMapsMapHits.get(), temporaryResolveMapsMapMisses.get());
                temporaryResolveMapsMap = null;
            }
        }
    }

    private boolean addResource(final String path, final AtomicBoolean resolverRefreshed) {
        this.initializing.lock();

        try {
            this.refreshResolverIfNecessary(resolverRefreshed);
            final Resource resource = this.resolver != null ? resolver.getResource(path) : null;
            if (resource != null) {
                boolean changed = doAddVanity(resource);
                if (this.useOptimizeAliasResolution && resource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                    changed |= doAddAlias(resource);
                }
                return changed;
            }

            return false;
        } finally {
            this.initializing.unlock();
        }
    }

    private boolean updateResource(final String path, final AtomicBoolean resolverRefreshed) {
        final boolean isValidVanityPath =  this.isValidVanityPath(path);
        if ( this.useOptimizeAliasResolution || isValidVanityPath) {
            this.initializing.lock();

            try {
                this.refreshResolverIfNecessary(resolverRefreshed);
                final Resource resource = this.resolver != null ? resolver.getResource(path) : null;
                if (resource != null) {
                    boolean changed = false;
                    if ( isValidVanityPath ) {
                        // we remove the old vanity path first
                        changed |= doRemoveVanity(path);

                        // add back vanity path
                        Resource contentRsrc = null;
                        if ( !resource.getName().equals(JCR_CONTENT)) {
                            // there might be a JCR_CONTENT child resource
                            contentRsrc = resource.getChild(JCR_CONTENT);
                        }
                        changed |= doAddVanity(contentRsrc != null ? contentRsrc : resource);
                    }
                    if (this.useOptimizeAliasResolution) {
                        changed |= doUpdateAlias(resource);
                    }

                    return changed;
                }
            } finally {
                this.initializing.unlock();
            }
        }

        return false;
    }

    private boolean removeResource(final String path, final AtomicBoolean resolverRefreshed) {
        boolean changed = false;
        final String actualContentPath = getActualContentPath(path);
        final String actualContentPathPrefix = actualContentPath + "/";

        for (final String target : this.vanityTargets.keySet()) {
            if (target.startsWith(actualContentPathPrefix) || target.equals(actualContentPath)) {
                changed |= removeVanityPath(target);
            }
        }
        if (this.useOptimizeAliasResolution) {
            final String pathPrefix = path + "/";
            for (final String contentPath : this.aliasMap.keySet()) {
                if (path.startsWith(contentPath + "/") || path.equals(contentPath)
                        || contentPath.startsWith(pathPrefix)) {
                    changed |= removeAlias(contentPath, path, resolverRefreshed);
                }
            }
        }
        return changed;
    }

    /**
     * Remove all aliases for the content path
     * @param contentPath The content path
     * @param path Optional sub path of the vanity path
     * @return {@code true} if a change happened
     */
    private boolean removeAlias(final String contentPath, final String path, final AtomicBoolean resolverRefreshed) {
        // if path is specified we first need to find out if it is
        // a direct child of vanity path but not jcr:content, or a jcr:content child of a direct child
        // otherwise we can discard the event
        boolean handle = true;
        String resourcePath = null;
        if ( path != null  && path.length() > contentPath.length()) {
            final String subPath = path.substring(contentPath.length() + 1);
            final int firstSlash = subPath.indexOf('/');
            if ( firstSlash == -1 ) {
                if ( subPath.equals(JCR_CONTENT) ) {
                    handle = false;
                }
                resourcePath = path;
            } else if ( subPath.lastIndexOf('/') == firstSlash) {
                if ( subPath.startsWith(JCR_CONTENT_PREFIX) || !subPath.endsWith(JCR_CONTENT_SUFFIX) ) {
                    handle = false;
                }
                resourcePath = ResourceUtil.getParent(path);
            } else {
                handle = false;
            }
        }
        else {
            resourcePath = contentPath;
        }
        if ( !handle ) {
            return false;
        }

        this.initializing.lock();
        try {
            final Map<String, String> aliasMapEntry = aliasMap.get(contentPath);
            if (aliasMapEntry != null) {
                this.refreshResolverIfNecessary(resolverRefreshed);

                for (Iterator<Map.Entry<String, String>> iterator = aliasMapEntry.entrySet().iterator(); iterator.hasNext(); ) {
                    final Map.Entry<String, String> entry = iterator.next();
                    String prefix = contentPath.endsWith("/") ? contentPath : contentPath + "/";
                    if ((prefix + entry.getValue()).startsWith(resourcePath)){
                        iterator.remove();
                    }
                }

                if (aliasMapEntry.isEmpty()) {
                    this.aliasMap.remove(contentPath);
                }

                Resource containingResource = this.resolver != null ? this.resolver.getResource(resourcePath) : null;

                if (containingResource != null) {
                    if (containingResource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                        doAddAlias(containingResource);
                    }
                    final Resource child = containingResource.getChild(JCR_CONTENT);
                    if (child != null && child.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS)) {
                        doAddAlias(child);
                    }
                }
            }
            return aliasMapEntry != null;
        } finally {
            this.initializing.unlock();
        }
    }

    private boolean removeVanityPath(final String path) {
        this.initializing.lock();
        try {
            return doRemoveVanity(path);
        } finally {
            this.initializing.unlock();
        }
    }

    /**
     * Update the configuration.
     * Does no locking and does not send an event at the end
     */
    private void doUpdateConfiguration() {
        final List<MapEntry> globalResolveMap = new ArrayList<>();
        final SortedMap<String, MapEntry> newMapMaps = new TreeMap<>();
        // load the /etc/map entries into the maps
        loadResolverMap(resolver, globalResolveMap, newMapMaps);
        // load the configuration into the resolver map
        loadConfiguration(factory, globalResolveMap);
        // load the configuration into the mapper map
        loadMapConfiguration(factory, newMapMaps);
        // sort global list and add to map
        Collections.sort(globalResolveMap);
        resolveMapsMap.put(GLOBAL_LIST_KEY, globalResolveMap);
        this.mapMaps = Collections.unmodifiableSet(new TreeSet<>(newMapMaps.values()));
    }

    private boolean doAddVanity(final Resource resource) {
        log.debug("doAddVanity getting {}", resource.getPath());

        boolean updateTheCache = isAllVanityPathEntriesCached() || vanityCounter.longValue() < this.factory.getMaxCachedVanityPathEntries();
        return null != loadVanityPath(resource, resolveMapsMap, vanityTargets, updateTheCache);
    }

    private boolean doRemoveVanity(final String path) {
        final String actualContentPath = getActualContentPath(path);
        final List <String> l = vanityTargets.remove(actualContentPath);
        if (l != null){
            for (final String s : l){
                final List<MapEntry> entries = this.resolveMapsMap.get(s);
                if (entries!= null) {
                    for (final Iterator<MapEntry> iterator =entries.iterator(); iterator.hasNext(); ) {
                        final MapEntry entry = iterator.next();
                        final String redirect = getMapEntryRedirect(entry);
                        if (redirect != null && redirect.equals(actualContentPath)) {
                            iterator.remove();
                        }
                    }
                }
                if (entries!= null && entries.isEmpty()) {
                    this.resolveMapsMap.remove(s);
                }
            }
            if (vanityCounter.longValue() > 0) {
                vanityCounter.addAndGet(-2);
            }
            return true;
        }
        return false;
    }

    private boolean doAddAlias(final Resource resource) {
        return loadAlias(resource, this.aliasMap);
    }

    /**
     * Update alias from a resource
     * @param resource The resource
     * @return {@code true} if any change
     */
    private boolean doUpdateAlias(final Resource resource) {
        final Resource containingResource;
        if (JCR_CONTENT.equals(resource.getName())) {
            containingResource = resource.getParent();
        } else {
            containingResource = resource;
        }

        if ( containingResource != null ) {
            final String containingResourceName = containingResource.getName();
            final String parentPath = ResourceUtil.getParent(containingResource.getPath());

            final Map<String, String> aliasMapEntry = parentPath == null ? null : aliasMap.get(parentPath);
            if (aliasMapEntry != null) {
                for (Iterator<Map.Entry<String, String>> iterator = aliasMapEntry.entrySet().iterator(); iterator.hasNext(); ) {
                    final Map.Entry<String, String> entry = iterator.next();
                    if (containingResourceName.equals(entry.getValue())){
                        iterator.remove();
                    }
                }
            }

            if (aliasMapEntry != null && aliasMapEntry.isEmpty()) {
                this.aliasMap.remove(parentPath);
            }

            boolean changed = aliasMapEntry != null;

            if ( containingResource.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS) ) {
                changed |= doAddAlias(containingResource);
            }
            final Resource child = containingResource.getChild(JCR_CONTENT);
            if ( child != null && child.getValueMap().containsKey(ResourceResolverImpl.PROP_ALIAS) ) {
                changed |= doAddAlias(child);
            }

            return changed;
        }
        return false;
    }

    /**
     * Cleans up this class.
     */
    public void dispose() {

        if (this.registration != null) {
            this.registration.unregister();
            this.registration = null;
        }

        /*
         * Cooperation with doInit: The same lock as used by doInit is acquired
         * thus preventing doInit from running and waiting for a concurrent
         * doInit to terminate. Once the lock has been acquired, the resource
         * resolver is null-ed (thus causing the init to terminate when
         * triggered the right after and prevent the doInit method from doing
         * any thing).
         */

        // wait at most 10 seconds for a notifcation during initialization
        boolean initLocked;
        try {
            initLocked = this.initializing.tryLock(10, TimeUnit.SECONDS);
        } catch (final InterruptedException ie) {
            initLocked = false;
        }

        try {
            if (!initLocked) {
                log.warn("dispose: Could not acquire initialization lock within 10 seconds; ongoing intialization may fail");
            }

            // immediately set the resolver field to null to indicate
            // that we have been disposed (this also signals to the
            // event handler to stop working
            final ResourceResolver oldResolver = this.resolver;
            this.resolver = null;

            if (oldResolver != null) {
                oldResolver.close();
            } else {
                log.warn("dispose: ResourceResolver has already been cleared before; duplicate call to dispose ?");
            }
        } finally {
            if (initLocked) {
                this.initializing.unlock();
            }
        }

        // clear the rest of the fields
        this.factory = null;
        this.eventAdmin = null;
    }

    @Override
    public List<MapEntry> getResolveMaps() {
        final List<MapEntry> entries = new ArrayList<>();
        for (final List<MapEntry> list : this.resolveMapsMap.values()) {
            entries.addAll(list);
        }
        Collections.sort(entries);
        return Collections.unmodifiableList(entries);
    }

    @Override
    public Iterator<MapEntry> getResolveMapsIterator(final String requestPath) {
        String key = null;
        final int firstIndex = requestPath.indexOf('/');
        final int secondIndex = requestPath.indexOf('/', firstIndex + 1);
        if (secondIndex != -1) {
            key = requestPath.substring(secondIndex);
        }

        return new MapEntryIterator(key, resolveMapsMap, this.factory.hasVanityPathPrecedence());
    }

    @Override
    public Collection<MapEntry> getMapMaps() {
        return mapMaps;
    }

    public boolean isOptimizeAliasResolutionEnabled() {
        return this.useOptimizeAliasResolution;
    }
    
    @Override
    public Map<String, String> getAliasMap(final String parentPath) {
        return aliasMap.get(parentPath);
    }

    @Override
    public Map<String, List<String>> getVanityPathMappings() {
        return Collections.unmodifiableMap(vanityTargets);
    }

    // special singleton entry for negative cache entries
    private static final List<MapEntry> NO_MAP_ENTRIES = Collections.emptyList();

    /**
     * get the MapEntry list containing all the nodes having a specific vanityPath
     */
    private List<MapEntry> getMapEntryList(String vanityPath) {
        List<MapEntry> mapEntries = null;

        boolean initFinished = vanityPathsProcessed.get();
        boolean probablyPresent = false;

        if (initFinished) {
            // total number of lookups after init (and when cache not complete)
            long current = this.vanityPathLookups.incrementAndGet();
            if (current >= Long.MAX_VALUE - 100000) {
                // reset counters when we get close the limit
                this.vanityPathLookups.set(1);
                this.vanityPathBloomNegative.set(0);
                this.vanityPathBloomFalsePositive.set(0);
                log.info("Vanity Path metrics reset to 0");
            }

            // init is done - check the bloom filter
            probablyPresent = BloomFilterUtils.probablyContains(vanityBloomFilter, vanityPath);
            log.trace("bloom filter lookup for {} -> {}", vanityPath, probablyPresent);

            if (!probablyPresent) {
                // filtered by Bloom filter
                this.vanityPathBloomNegative.incrementAndGet();
            }
        }

        if (!initFinished || probablyPresent) {

            mapEntries = this.resolveMapsMap.get(vanityPath);

            if (mapEntries == null) {
                if (!initFinished && temporaryResolveMapsMap != null) {
                    mapEntries = temporaryResolveMapsMap.get(vanityPath);
                    if (mapEntries != null) {
                        temporaryResolveMapsMapHits.incrementAndGet();
                        log.trace("getMapEntryList: using temp map entries for {} -> {}", vanityPath, mapEntries);
                    } else {
                        temporaryResolveMapsMapMisses.incrementAndGet();
                    }
                }
                if (mapEntries == null) {
                    Map<String, List<MapEntry>> mapEntry = getVanityPaths(vanityPath);
                    mapEntries = mapEntry.get(vanityPath);
                    if (!initFinished && temporaryResolveMapsMap != null) {
                        log.trace("getMapEntryList: caching map entries for {} -> {}", vanityPath, mapEntries);
                        temporaryResolveMapsMap.put(vanityPath, mapEntries == null ? NO_MAP_ENTRIES : mapEntries);
                    }
                }
            }
            if (mapEntries == null && probablyPresent) {
                // Bloom filter had a false positive
                this.vanityPathBloomFalsePositive.incrementAndGet();
            }
        }

        return mapEntries == NO_MAP_ENTRIES ? null : mapEntries;
    }

    /**
     * Refresh the resource resolver if not already done
     * @param resolverRefreshed Boolean flag containing the state if the resolver
     *                          has been refreshed. True in any case when this
     *                          method returns
     */
    private void refreshResolverIfNecessary(final AtomicBoolean resolverRefreshed) {
        if ( resolverRefreshed.compareAndSet(false, true) ) {
            this.resolver.refresh();
        }
    }

    /**
     * Checks if the path affects the map configuration. If it does
     * the configuration is updated.
     * @param path The changed path (could be add/remove/update)
     * @param hasReloadedConfig If this is already true, the config will not be reloaded
     * @param resolverRefreshed Boolean flag handling resolver refresh
     * @param isDelete If this is a delete event
     * @return {@code true} if the configuration has been updated, {@code false} if
     *         the path does not affect a config change, {@code null} if the config has already
     *         been reloaded.
     */
    private Boolean handleConfigurationUpdate(final String path,
            final AtomicBoolean hasReloadedConfig,
            final AtomicBoolean resolverRefreshed,
            final boolean isDelete) {
        if ( this.factory.isMapConfiguration(path)
             || (isDelete && this.factory.getMapRoot().startsWith(path + "/")) ) {
            if ( hasReloadedConfig.compareAndSet(false, true) ) {
                this.initializing.lock();

                try {
                    if (this.resolver != null) {
                        refreshResolverIfNecessary(resolverRefreshed);
                        doUpdateConfiguration();
                    }
                } finally {
                    this.initializing.unlock();
                }
                return true;
            }
            return null;
        }
        return false;
    }

    // ---------- ResourceChangeListener interface

    /**
     * Handles the change to any of the node properties relevant for vanity URL
     * mappings. The {@link #MapEntries(MapConfigurationProvider, BundleContext, EventAdmin, StringInterpolationProvider, Optional)}
     * constructor makes sure the event listener is registered to only get
     * appropriate events.
     */
    @Override
    public void onChange(final List<ResourceChange> changes) {

        final boolean inStartup = !vanityPathsProcessed.get();

        final AtomicBoolean resolverRefreshed = new AtomicBoolean(false);

        // send the change event only once
        boolean sendEvent = false;

        // the config needs to be reloaded only once
        final AtomicBoolean hasReloadedConfig = new AtomicBoolean(false);

        for (final ResourceChange rc : changes) {

            final ResourceChange.ChangeType type = rc.getType();
            final String path = rc.getPath();

            log.debug("onChange, type={}, path={}", rc.getType(), path);

            // don't care for system area
            if (path.startsWith(JCR_SYSTEM_PREFIX)) {
                continue;
            }

            // during startup: just enqueue the events
            if (inStartup) {
                if (type == ResourceChange.ChangeType.REMOVED || type == ResourceChange.ChangeType.ADDED
                        || type == ResourceChange.ChangeType.CHANGED) {
                    Map.Entry<String, ResourceChange.ChangeType> entry = new SimpleEntry<>(path, type);
                    log.trace("enqueue: {}", entry);
                    resourceChangeQueue.add(entry);
                }
            } else {
                boolean changed = handleResourceChange(type, path, resolverRefreshed, hasReloadedConfig);

                if (changed) {
                    sendEvent = true;
                }
            }
        }

        if (sendEvent) {
            this.sendChangeEvent();
        }
    }

    private boolean handleResourceChange(ResourceChange.ChangeType type, String path, AtomicBoolean resolverRefreshed,
            AtomicBoolean hasReloadedConfig) {
        boolean changed = false;

        // removal of a resource is handled differently
        if (type == ResourceChange.ChangeType.REMOVED) {
            final Boolean result = handleConfigurationUpdate(path, hasReloadedConfig, resolverRefreshed, true);
            if (result != null) {
                if (result) {
                    changed = true;
                } else {
                    changed |= removeResource(path, resolverRefreshed);
                }
            }
            // session.move() is handled differently see also SLING-3713 and
        } else if (type == ResourceChange.ChangeType.ADDED) {
            final Boolean result = handleConfigurationUpdate(path, hasReloadedConfig, resolverRefreshed, false);
            if (result != null) {
                if (result) {
                    changed = true;
                } else {
                    changed |= addResource(path, resolverRefreshed);
                }
            }
        } else if (type == ResourceChange.ChangeType.CHANGED) {
            final Boolean result = handleConfigurationUpdate(path, hasReloadedConfig, resolverRefreshed, false);
            if (result != null) {
                if (result) {
                    changed = true;
                } else {
                    changed |= updateResource(path, resolverRefreshed);
                }
            }
        }

        return changed;
    }

    // ---------- internal

    private byte[] createVanityBloomFilter() throws IOException {
        return BloomFilterUtils.createFilter(VANITY_BLOOM_FILTER_MAX_ENTRIES, this.factory.getVanityBloomFilterMaxBytes());
    }

    private boolean isAllVanityPathEntriesCached() {
        return this.factory.getMaxCachedVanityPathEntries() == -1;
    }

    // escapes string for use as literal in JCR SQL within single quotes
    private static String queryLiteral(String input) {
        return input.replace("'", "''");
    }

    /**
     * get the vanity paths  Search for all nodes having a specific vanityPath
     */
    private Map<String, List<MapEntry>> getVanityPaths(String vanityPath) {

        Map<String, List<MapEntry>> entryMap = new HashMap<>();

        final String queryString = String.format(
                "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus] FROM [nt:base] "
                        + "WHERE NOT isdescendantnode('%s') AND ([sling:vanityPath]='%s' OR [sling:vanityPath]='%s') "
                        + "ORDER BY [sling:vanityOrder] DESC",
                JCR_SYSTEM_PATH, queryLiteral(vanityPath), queryLiteral(vanityPath.substring(1)));

        try (ResourceResolver queryResolver = factory.getServiceResourceResolver(factory.getServiceUserAuthenticationInfo("mapping"));) {
            long totalCount = 0;
            long totalValid = 0;
            log.debug("start vanityPath query: {}", queryString);
            final Iterator<Resource> i = queryResolver.findResources(queryString, "JCR-SQL2");
            log.debug("end vanityPath query");
            while (i.hasNext()) {
                totalCount += 1;
                final Resource resource = i.next();
                boolean isValid = false;
                for(final Path sPath : this.factory.getObservationPaths()) {
                    if ( sPath.matches(resource.getPath())) {
                        isValid = true;
                        break;
                    }
                }
                if ( isValid ) {
                    totalValid += 1;
                    if (this.vanityPathsProcessed.get() && (this.factory.isMaxCachedVanityPathEntriesStartup() || vanityCounter.longValue() < this.factory.getMaxCachedVanityPathEntries())) {
                        loadVanityPath(resource, resolveMapsMap, vanityTargets, true);
                        entryMap = resolveMapsMap;
                    } else {
                        final Map <String, List<String>> targetPaths = new HashMap<>();
                        loadVanityPath(resource, entryMap, targetPaths, true);
                    }
                }
            }
            log.debug("read {} ({} valid) vanityPaths", totalCount, totalValid);
        } catch (LoginException e) {
            log.error("Exception while obtaining queryResolver", e);
        }
        return entryMap;
    }

    /**
     * Check if the path is a valid vanity path
     * @param path The resource path to check
     * @return {@code true} if this is valid, {@code false} otherwise
     */
    private boolean isValidVanityPath(final String path){
        if (path == null) {
            throw new IllegalArgumentException("Unexpected null path");
        }

        // ignore system tree
        if (path.startsWith(JCR_SYSTEM_PREFIX)) {
            log.debug("isValidVanityPath: not valid {}", path);
            return false;
        }

        // check white list
        if ( this.factory.getVanityPathConfig() != null ) {
            boolean allowed = false;
            for(final VanityPathConfig config : this.factory.getVanityPathConfig()) {
                if ( path.startsWith(config.prefix) ) {
                    allowed = !config.isExclude;
                    break;
                }
            }
            if ( !allowed ) {
                log.debug("isValidVanityPath: not valid as not in white list {}", path);
                return false;
            }
        }
        return true;
    }

    private String getActualContentPath(final String path){
        final String checkPath;
        if ( path.endsWith(JCR_CONTENT_SUFFIX) ) {
            checkPath = ResourceUtil.getParent(path);
        } else {
            checkPath = path;
        }
        return checkPath;
    }

    private String getMapEntryRedirect(final  MapEntry mapEntry) {
        String[] redirect = mapEntry.getRedirect();
        if (redirect.length > 1) {
            log.warn("something went wrong, please restart the bundle");
            return null;
        }

        String path = redirect[0];
        if (path.endsWith("$1")) {
            path = path.substring(0, path.length() - "$1".length());
        } else if (path.endsWith(".html")) {
            path = path.substring(0, path.length() - ".html".length());
        }

        return path;
    }

    /**
     * Send an OSGi event
     */
    private void sendChangeEvent() {
        final EventAdmin local = this.eventAdmin;
        if (local != null) {
            final Event event = new Event(SlingConstants.TOPIC_RESOURCE_RESOLVER_MAPPING_CHANGED,
                            (Dictionary<String, ?>) null);
            local.postEvent(event);
        }
    }

    private void loadResolverMap(final ResourceResolver resolver, final List<MapEntry> entries, final Map<String, MapEntry> mapEntries) {
        // the standard map configuration
        final Resource res = resolver.getResource(this.factory.getMapRoot());
        if (res != null) {
            gather(resolver, entries, mapEntries, res, "");
        }
    }

    private void gather(final ResourceResolver resolver, final List<MapEntry> entries, final Map<String, MapEntry> mapEntries,
                    final Resource parent, final String parentPath) {
        // scheme list
        final Iterator<Resource> children = parent.listChildren();
        while (children.hasNext()) {
            final Resource child = children.next();
            final ValueMap vm = ResourceUtil.getValueMap(child);

            String name = vm.get(PROP_REG_EXP, String.class);
            boolean trailingSlash = false;
            if (name == null) {
                name = child.getName().concat("/");
                trailingSlash = true;
            }
            // Check for placeholders and replace if needed
            name = stringInterpolationProvider.substitute(name);

            final String childPath = parentPath.concat(name);

            // gather the children of this entry (only if child is not end
            // hooked)
            if (!childPath.endsWith("$")) {

                // add trailing slash to child path to append the child
                String childParent = childPath;
                if (!trailingSlash) {
                    childParent = childParent.concat("/");
                }

                gather(resolver, entries, mapEntries, child, childParent);
            }

            // add resolution entries for this node
            MapEntry childResolveEntry = null;
            try{
                childResolveEntry=MapEntry.createResolveEntry(childPath, child, trailingSlash);
            }catch (IllegalArgumentException iae){
                //ignore this entry
                log.debug("ignored entry due exception ",iae);
            }
            if (childResolveEntry != null) {
                entries.add(childResolveEntry);
            }

            // add map entries for this node
            final List<MapEntry> childMapEntries = MapEntry.createMapEntry(childPath, child, trailingSlash);
            if (childMapEntries != null) {
                for (final MapEntry mapEntry : childMapEntries) {
                    addMapEntry(mapEntries, mapEntry.getPattern(), mapEntry.getRedirect()[0], mapEntry.getStatus());
                }
            }

        }
    }

    /**
     * Add an entry to the resolve map.
     */
    private boolean addEntry(final Map<String, List<MapEntry>> entryMap, final String key, final MapEntry entry) {

        if (entry == null) {
            log.trace("trying to add null entry for {}", key);
            return false;
        } else {
            List<MapEntry> entries = entryMap.get(key);
            if (entries == null) {
                entries = new ArrayList<>();
                entries.add(entry);
                entryMap.put(key, entries);
            } else {
                List<MapEntry> entriesCopy = new ArrayList<>(entries);
                entriesCopy.add(entry);
                // and finally sort list
                Collections.sort(entriesCopy);
                entryMap.put(key, entriesCopy);
                int size = entriesCopy.size();
                if (size == 10) {
                    log.debug(">= 10 MapEntries for {} - check your configuration", key);
                } else if (size == 100) {
                    log.info(">= 100 MapEntries for {} - check your configuration", key);
                }
            }
            return true;
        }
    }

    /**
     * Load aliases - Search for all nodes (except under /jcr:system) below
     * configured alias locations having the sling:alias property
     */
    private Map<String, Map<String, String>> loadAliases(final ResourceResolver resolver) {
        final Map<String, Map<String, String>> map = new ConcurrentHashMap<>();
        final String queryString = generateAliasQuery();

        log.debug("start alias query: {}", queryString);
        long queryStart = System.nanoTime();
        final Iterator<Resource> i = resolver.findResources(queryString, "sql");
        long queryElapsed = System.nanoTime() - queryStart;
        log.debug("end alias query; elapsed {}ms", TimeUnit.NANOSECONDS.toMillis(queryElapsed));

        long count = 0;
        long processStart = System.nanoTime();
        while (i.hasNext()) {
            count += 1;
            loadAlias(i.next(), map);
        }
        long processElapsed = System.nanoTime() - processStart;
        log.debug("processed {} aliases in {}ms", count, TimeUnit.NANOSECONDS.toMillis(processElapsed));

        return map;
    }

    /*
     * generate alias query based on configured alias locations
     */
    private String generateAliasQuery() {
        final Set<String> allowedLocations = this.factory.getAllowedAliasLocations();

        StringBuilder baseQuery = new StringBuilder(ALIAS_BASE_QUERY_DEFAULT);
        baseQuery.append(" ").append("WHERE");

        if (allowedLocations.isEmpty()) {
            String jcrSystemPath = StringUtils.removeEnd(JCR_SYSTEM_PREFIX, "/");
            baseQuery.append(" (").append("NOT ISDESCENDANTNODE(page,'").append(queryLiteral(jcrSystemPath)).append("'))");
        } else {
            Iterator<String> pathIterator = allowedLocations.iterator();
            baseQuery.append("(");
            String sep = "";
            while (pathIterator.hasNext()) {
                String prefix = pathIterator.next();
                baseQuery.append(sep).append("ISDESCENDANTNODE(page,'").append(queryLiteral(prefix)).append("')");
                sep = " OR ";
            }
            baseQuery.append(")");
        }

        baseQuery.append(" AND sling:alias IS NOT NULL");
        return baseQuery.toString();
    }

    /**
     * Load alias given a resource
     */
    private boolean loadAlias(final Resource resource, Map<String, Map<String, String>> map) {

        // resource containing the alias
        final Resource containingResource;

        if (JCR_CONTENT.equals(resource.getName())) {
            containingResource = resource.getParent();
        } else {
            containingResource = resource;
        }

        final Resource parent = containingResource.getParent();

        if (parent == null) {
            log.debug("parent is null for alias on {}.", resource.getName());
            return false;
        }
        else {
            // resource the alias is for
            String resourceName = containingResource.getName();

            // parent path of that resource
            String parentPath = parent.getPath();

            boolean hasAlias = false;

            // require properties
            final ValueMap props = resource.getValueMap();
            final String[] aliasArray = props.get(ResourceResolverImpl.PROP_ALIAS, String[].class);

            if (aliasArray != null) {
                log.debug("Found alias, total size {}", aliasArray.length);
                for (final String alias : aliasArray) {
                    if (isAliasValid(alias)) {
                        log.warn("Encountered invalid alias {} under parent path {}. Refusing to use it.", alias, parentPath);
                    } else {
                        Map<String, String> parentMap = map.get(parentPath);

                        if (parentMap == null) {
                            parentMap = new LinkedHashMap<>();
                            map.put(parentPath, parentMap);
                        }

                        String current = parentMap.get(alias);
                        if (current != null) {
                            log.warn(
                                    "Encountered duplicate alias {} under parent path {}. Refusing to replace current target {} with {}.",
                                    alias, parentPath, current, resourceName);
                        } else {
                            parentMap.put(alias, resourceName);
                            hasAlias = true;
                        }
                    }
                }
            }

            return hasAlias;
        }
    }

    /**
     * Check alias syntax
     */
    private static boolean isAliasValid(String alias) {
        boolean invalid = alias.equals("..") || alias.equals(".");
        if (!invalid) {
            for (final char c : alias.toCharArray()) {
                // invalid if / or # or a ?
                if (c == '/' || c == '#' || c == '?') {
                    invalid = true;
                    break;
                }
            }
        }
        return invalid;
    }

    private Iterator<Resource> queryAllVanityPaths(String query) {
        log.debug("start vanityPath query: {}", query);
        long queryStart = System.nanoTime();
        final Iterator<Resource> it = resolver.findResources(query, "JCR-SQL2");
        long queryElapsed = System.nanoTime() - queryStart;
        log.debug("end vanityPath query; elapsed {}ms", TimeUnit.NANOSECONDS.toMillis(queryElapsed));
        return it;
    }

    private class PagedQueryIterator implements Iterator<Resource> {

        private ResourceResolver resolver;
        private String query;
        private String lastPath = "";
        private Iterator<Resource> it;
        private int count = 0;
        private int page = 0;
        private int pageSize = Integer.getInteger("sling.vanityPath.pageSize", 2000);
        private Resource next = null;

        public PagedQueryIterator(ResourceResolver resolver, String query) {
            this.resolver = resolver;
            this.query = query;
            nextPage();
        }

        private void nextPage() {
            count = 0;
            String tquery = String.format(query, queryLiteral(lastPath));
            log.debug("start vanity path query (page {}): {}", page, tquery);
            long queryStart = System.nanoTime();
            this.it = resolver.findResources(tquery, "JCR-SQL2");
            long queryElapsed = System.nanoTime() - queryStart;
            log.debug("end vanity path query (page {}); elapsed {}ms", page, TimeUnit.NANOSECONDS.toMillis(queryElapsed));
            page += 1;
        }

        private Resource getNext() throws NoSuchElementException {
            Resource resource = it.next();
            count += 1;
            final String[] paths = resource.getValueMap().get(PROP_VANITY_PATH, new String[0]);
            if (paths.length > 0) {
                String p = paths[0];
                if (p.compareTo(lastPath) < 0) {
                    String message = String.format(
                            "unexpected query result in page %d, vanity path of '%s' despite querying for > '%s'", (page - 1), p,
                            lastPath);
                    log.error(message);
                    throw new RuntimeException(message);
                }
                // start next page?
                if (count > pageSize && !p.equals(lastPath)) {
                    lastPath = p;
                    nextPage();
                }
            }

            return resource;
        }

        @Override
        public boolean hasNext() {
            if (next == null) {
                try {
                    next = getNext();
                } catch (NoSuchElementException ex) {
                    // there are no more
                    next = null;
                }
            }
            return next != null;
        }

        @Override
        public Resource next() throws NoSuchElementException {
            Resource result = next != null ? next : getNext();
            next = null;
            return result;
        }
    }

    /**
     * Load vanity paths - search for all nodes (except under /jcr:system)
     * having a sling:vanityPath property
     */
    private Map<String, List<String>> loadVanityPaths(ResourceResolver resolver) {
        final Map<String, List<String>> targetPaths = new ConcurrentHashMap<>();
        final String baseQueryString = "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus]" + " FROM [nt:base]"
                + " WHERE NOT isdescendantnode('" + queryLiteral(JCR_SYSTEM_PATH) + "')"
                + " AND [sling:vanityPath] IS NOT NULL";

        boolean supportsSort = true;
        Iterator<Resource> it;
        try {
            final String queryStringWithSort = baseQueryString + " AND FIRST([sling:vanityPath]) > '%s' ORDER BY FIRST([sling:vanityPath])";
            it = new PagedQueryIterator(resolver, queryStringWithSort);
        } catch (QuerySyntaxException ex) {
            log.debug("sort with first() not supported, falling back to base query");
            supportsSort = false;
            it = queryAllVanityPaths(baseQueryString);
        }

        long count = 0;
        long countInScope = 0;
        long processStart = System.nanoTime();
        String previousVanityPath = null;

        while (it.hasNext()) {
            count += 1;
            final Resource resource = it.next();
            final String resourcePath = resource.getPath();
            if (Stream.of(this.factory.getObservationPaths()).anyMatch(path -> path.matches(resourcePath))) {
                countInScope += 1;
                final boolean addToCache = isAllVanityPathEntriesCached()
                        || vanityCounter.longValue() < this.factory.getMaxCachedVanityPathEntries();
                String firstVanityPath = loadVanityPath(resource, resolveMapsMap, targetPaths, addToCache);
                if (supportsSort && firstVanityPath != null) {
                    if (previousVanityPath != null && firstVanityPath.compareTo(previousVanityPath) < 0) {
                        log.error("Sorting by first(vanityPath) does not appear to work; got " + firstVanityPath + " after " + previousVanityPath);
                    }
                    previousVanityPath = firstVanityPath;
               }
            }
        }
        long processElapsed = System.nanoTime() - processStart;
        log.debug("processed {} vanityPaths (of which {} in scope) in {}ms", count, countInScope, TimeUnit.NANOSECONDS.toMillis(processElapsed));
        if (!isAllVanityPathEntriesCached()) {
            if (countInScope > this.factory.getMaxCachedVanityPathEntries()) {
                log.warn("Number of vanity paths in scope ({}) exceeds configured cache size ({}); handling of uncached vanity paths will be much slower. Consider increasing the cache size or decreasing the number of vanity paths.", countInScope, this.factory.getMaxCachedVanityPathEntries());
            } else if (countInScope > (this.factory.getMaxCachedVanityPathEntries() / 10) * 9) {
                log.info("Number of vanity paths in scope ({}) within 10% of configured cache size ({})", countInScope, this.factory.getMaxCachedVanityPathEntries());
            }
        }

        return targetPaths;
    }

    /**
     * Load vanity path given a resource
     * 
     * @return first vanity path or {@code null}
     */
    private String loadVanityPath(final Resource resource, final Map<String, List<MapEntry>> entryMap, final Map <String, List<String>> targetPaths, boolean addToCache) {

        if (!isValidVanityPath(resource.getPath())) {
            return null;
        }

        final ValueMap props = resource.getValueMap();
        long vanityOrder = props.get(PROP_VANITY_ORDER, 0L);

        // url is ignoring scheme and host.port and the path is
        // what is stored in the sling:vanityPath property
        boolean hasVanityPath = false;
        final String[] pVanityPaths = props.get(PROP_VANITY_PATH, new String[0]);
        if (log.isTraceEnabled()) {
            log.trace("vanity paths on {}: {}", resource.getPath(), Arrays.asList(pVanityPaths));
        }

        for (final String pVanityPath : pVanityPaths) {
            final String[] result = this.getVanityPathDefinition(pVanityPath);
            if (result != null) {
                hasVanityPath = true;
                final String url = result[0] + result[1];
                // redirect target is the node providing the
                // sling:vanityPath
                // property (or its parent if the node is called
                // jcr:content)
                final Resource redirectTarget;
                if (JCR_CONTENT.equals(resource.getName())) {
                    redirectTarget = resource.getParent();
                } else {
                    redirectTarget = resource;
                }
                final String redirect = redirectTarget.getPath();
                final String redirectName = redirectTarget.getName();

                // whether the target is attained by a external redirect or
                // by an internal redirect is defined by the sling:redirect
                // property
                final int status = props.get(PROP_REDIRECT_EXTERNAL, false) ? props.get(
                        PROP_REDIRECT_EXTERNAL_REDIRECT_STATUS, factory.getDefaultVanityPathRedirectStatus())
                        : -1;

                final String checkPath = result[1];

                boolean addedEntry;
                if (addToCache) {
                    if (redirectName.indexOf('.') > -1) {
                        // 1. entry with exact match
                        this.addEntry(entryMap, checkPath, getMapEntry(url + "$", status, false, vanityOrder, redirect));

                        final int idx = redirectName.lastIndexOf('.');
                        final String extension = redirectName.substring(idx + 1);

                        // 2. entry with extension
                        addedEntry = this.addEntry(entryMap, checkPath, getMapEntry(url + "\\." + extension, status, false, vanityOrder, redirect));
                    } else {
                        // 1. entry with exact match
                        this.addEntry(entryMap, checkPath, getMapEntry(url + "$", status, false, vanityOrder, redirect + ".html"));

                        // 2. entry with match supporting selectors and extension
                        addedEntry = this.addEntry(entryMap, checkPath, getMapEntry(url + "(\\..*)", status, false, vanityOrder, redirect + "$1"));
                    }
                    if (addedEntry) {
                        // 3. keep the path to return
                        this.updateTargetPaths(targetPaths, redirect, checkPath);
                        //increment only if the instance variable
                        if (entryMap == resolveMapsMap) {
                            vanityCounter.addAndGet(2);
                        }

                        // update bloom filter
                        BloomFilterUtils.add(vanityBloomFilter, checkPath);
                    }
                } else {
                    // update bloom filter
                    BloomFilterUtils.add(vanityBloomFilter, checkPath);
                }
            }
        }
        return hasVanityPath ? pVanityPaths[0] : null;
    }

    private void updateTargetPaths(final Map<String, List<String>> targetPaths, final String key, final String entry) {
        if (entry == null) {
           return;
        }
        List<String> entries = targetPaths.get(key);
        if (entries == null) {
            entries = new ArrayList<>();
            targetPaths.put(key, entries);
        }
        entries.add(entry);
    }

    /**
     * Create the vanity path definition. String array containing:
     * {protocol}/{host}[.port] {absolute path}
     */
    private String[] getVanityPathDefinition(final String pVanityPath) {
        String[] result = null;
        if (pVanityPath != null) {
            final String info = pVanityPath.trim();
            if (info.length() > 0) {
                String prefix = null;
                String path = null;
                // check for url
                if (info.indexOf(":/") > -1) {
                    try {
                        final URL u = new URL(info);
                        prefix = u.getProtocol() + '/' + u.getHost() + '.' + u.getPort();
                        path = u.getPath();
                    } catch (final MalformedURLException e) {
                        log.warn("Ignoring malformed vanity path {}", pVanityPath);
                    }
                } else {
                    prefix = "^" + ANY_SCHEME_HOST;
                    if (!info.startsWith("/")) {
                        path = "/" + info;
                    } else {
                        path = info;
                    }
                }

                // remove extension
                if (prefix != null) {
                    final int lastSlash = path.lastIndexOf('/');
                    final int firstDot = path.indexOf('.', lastSlash + 1);
                    if (firstDot != -1) {
                        path = path.substring(0, firstDot);
                        log.warn("Removing extension from vanity path {}", pVanityPath);
                    }
                    result = new String[] { prefix, path };
                }
            }
        }
        return result;
    }

    private void loadConfiguration(final MapConfigurationProvider factory, final List<MapEntry> entries) {
        // virtual uris
        final Map<String, String> virtuals = factory.getVirtualURLMap();
        if (virtuals != null) {
            for (final Entry<String, String> virtualEntry : virtuals.entrySet()) {
                final String extPath = virtualEntry.getKey();
                final String intPath = virtualEntry.getValue();
                if (!extPath.equals(intPath)) {
                    // this regular expression must match the whole URL !!
                    final String url = "^" + ANY_SCHEME_HOST + extPath + "$";
                    final String redirect = intPath;
                    MapEntry mapEntry = getMapEntry(url, -1, false, redirect);
                    if (mapEntry!=null){
                        entries.add(mapEntry);
                    }
                }
            }
        }

        // URL Mappings
        final Mapping[] mappings = factory.getMappings();
        if (mappings != null) {
            final Map<String, List<String>> map = new HashMap<>();
            for (final Mapping mapping : mappings) {
                if (mapping.mapsInbound()) {
                    final String url = mapping.getTo();
                    final String alias = mapping.getFrom();
                    if (url.length() > 0) {
                        List<String> aliasList = map.get(url);
                        if (aliasList == null) {
                            aliasList = new ArrayList<>();
                            map.put(url, aliasList);
                        }
                        aliasList.add(alias);
                    }
                }
            }

            for (final Entry<String, List<String>> entry : map.entrySet()) {
                MapEntry mapEntry = getMapEntry(ANY_SCHEME_HOST + entry.getKey(), -1, false, entry.getValue().toArray(new String[0]));
                if (mapEntry!=null){
                    entries.add(mapEntry);
                }
            }
        }
    }

    private void loadMapConfiguration(final MapConfigurationProvider factory, final Map<String, MapEntry> entries) {
        // URL Mappings
        final Mapping[] mappings = factory.getMappings();
        if (mappings != null) {
            for (int i = mappings.length - 1; i >= 0; i--) {
                final Mapping mapping = mappings[i];
                if (mapping.mapsOutbound()) {
                    final String url = mapping.getTo();
                    final String alias = mapping.getFrom();
                    if (!url.equals(alias)) {
                        addMapEntry(entries, alias, url, -1);
                    }
                }
            }
        }

        // virtual uris
        final Map<String, String> virtuals = factory.getVirtualURLMap();
        if (virtuals != null) {
            for (final Entry<String, String> virtualEntry : virtuals.entrySet()) {
                final String extPath = virtualEntry.getKey();
                final String intPath = virtualEntry.getValue();
                if (!extPath.equals(intPath)) {
                    // this regular expression must match the whole URL !!
                    final String path = "^" + intPath + "$";
                    final String url = extPath;
                    addMapEntry(entries, path, url, -1);
                }
            }
        }
    }

    private void addMapEntry(final Map<String, MapEntry> entries, final String path, final String url, final int status) {
        MapEntry entry = entries.get(path);
        if (entry == null) {
            entry = getMapEntry(path, status, false, url);
        } else {
            final String[] redir = entry.getRedirect();
            final String[] newRedir = new String[redir.length + 1];
            System.arraycopy(redir, 0, newRedir, 0, redir.length);
            newRedir[redir.length] = url;
            entry = getMapEntry(entry.getPattern(), entry.getStatus(), false, newRedir);
        }
        if (entry!=null){
            entries.put(path, entry);
        }
    }

    private final AtomicLong lastTimeLogged = new AtomicLong(-1);

    private final long LOGGING_ERROR_PERIOD = 1000 * 60 * 5;

    @Override
    public void logDisableAliasOptimization() {
        this.logDisableAliasOptimization(null);
    }

    private void logDisableAliasOptimization(final Exception e) {
        if ( e != null ) {
            log.error("Unexpected problem during initialization of optimize alias resolution. Therefore disabling optimize alias resolution. Please fix the problem.", e);
        } else {
            final long now = System.currentTimeMillis();
            if ( now - lastTimeLogged.getAndSet(now) > LOGGING_ERROR_PERIOD) {
                log.error("A problem occured during initialization of optimize alias resolution. Optimize alias resolution is disabled. Check the logs for the reported problem.", e);
            }
        }

    }

    private final class MapEntryIterator implements Iterator<MapEntry> {

        private final Map<String, List<MapEntry>> resolveMapsMap;

        private String key;

        private MapEntry next;

        private final Iterator<MapEntry> globalListIterator;
        private MapEntry nextGlobal;

        private Iterator<MapEntry> specialIterator;
        private MapEntry nextSpecial;

        private boolean vanityPathPrecedence;

        public MapEntryIterator(final String startKey, final Map<String, List<MapEntry>> resolveMapsMap, final boolean vanityPathPrecedence) {
            this.key = startKey;
            this.resolveMapsMap = resolveMapsMap;
            this.globalListIterator = this.resolveMapsMap.get(GLOBAL_LIST_KEY).iterator();
            this.vanityPathPrecedence = vanityPathPrecedence;
            this.seek();
        }

        /**
         * @see java.util.Iterator#hasNext()
         */
        @Override
        public boolean hasNext() {
            return this.next != null;
        }

        /**
         * @see java.util.Iterator#next()
         */
        @Override
        public MapEntry next() {
            if (this.next == null) {
                throw new NoSuchElementException();
            }
            final MapEntry result = this.next;
            this.seek();
            return result;
        }

        /**
         * @see java.util.Iterator#remove()
         */
        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        private void seek() {
            if (this.nextGlobal == null && this.globalListIterator.hasNext()) {
                this.nextGlobal = this.globalListIterator.next();
            }
            if (this.nextSpecial == null) {
                if (specialIterator != null && !specialIterator.hasNext()) {
                    specialIterator = null;
                }
                while (specialIterator == null && key != null) {
                    // remove selectors and extension
                    final int lastSlashPos = key.lastIndexOf('/');
                    final int lastDotPos = key.indexOf('.', lastSlashPos);
                    if (lastDotPos != -1) {
                        key = key.substring(0, lastDotPos);
                    }

                    final List<MapEntry> special;
                    if (MapEntries.this.isAllVanityPathEntriesCached() && MapEntries.this.vanityPathsProcessed.get()) {
                        special = this.resolveMapsMap.get(key);
                    } else {
                        special = MapEntries.this.getMapEntryList(key); 
                    }
                    if (special != null) {
                        specialIterator = special.iterator();
                    }
                    // recurse to the parent
                    if (key.length() > 1) {
                        final int lastSlash = key.lastIndexOf("/");
                        if (lastSlash == 0) {
                            key = null;
                        } else {
                            key = key.substring(0, lastSlash);
                        }
                    } else {
                        key = null;
                    }
                }
                if (this.specialIterator != null && this.specialIterator.hasNext()) {
                    this.nextSpecial = this.specialIterator.next();
                }
            }
            if (this.nextSpecial == null) {
                this.next = this.nextGlobal;
                this.nextGlobal = null;
            } else if (!this.vanityPathPrecedence){
                if (this.nextGlobal == null) {
                    this.next = this.nextSpecial;
                    this.nextSpecial = null;
                } else if (this.nextGlobal.getPattern().length() >= this.nextSpecial.getPattern().length()) {
                    this.next = this.nextGlobal;
                    this.nextGlobal = null;

                }else {
                    this.next = this.nextSpecial;
                    this.nextSpecial = null;
                }
            } else {
                this.next = this.nextSpecial;
                this.nextSpecial = null;
            }
        }
    };

    private MapEntry getMapEntry(String url, final int status, final boolean trailingSlash,
            final String... redirect){

        MapEntry mapEntry = null;
        try{
            mapEntry = new MapEntry(url, status, trailingSlash, 0, redirect);
        }catch (IllegalArgumentException iae){
            //ignore this entry
            log.debug("ignored entry due exception ",iae);
        }
        return mapEntry;
    }

    private MapEntry getMapEntry(String url, final int status, final boolean trailingSlash, long order,
            final String... redirect){

        MapEntry mapEntry = null;
        try{
            mapEntry = new MapEntry(url, status, trailingSlash, order, redirect);
        }catch (IllegalArgumentException iae){
            //ignore this entry
            log.debug("ignored entry due exception ",iae);
        }
        return mapEntry;
    }
}
