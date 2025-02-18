/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.resourceresolver.impl.mapping;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.observation.ResourceChange;
import org.apache.sling.api.resource.observation.ResourceChange.ChangeType;
import org.apache.sling.api.resource.path.Path;
import org.apache.sling.api.wrappers.ValueMapDecorator;
import org.apache.sling.resourceresolver.impl.ResourceResolverImpl;
import org.apache.sling.resourceresolver.impl.ResourceResolverMetrics;
import org.apache.sling.resourceresolver.impl.mapping.MapConfigurationProvider.VanityPathConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.event.EventAdmin;

@RunWith(Parameterized.class)
public class MapEntriesTest extends AbstractMappingMapEntriesTest {

    private MapEntries mapEntries;

    @Mock
    private MapConfigurationProvider resourceResolverFactory;

    @Mock
    private BundleContext bundleContext;

    @Mock
    private Bundle bundle;

    @Mock
    private ResourceResolver resourceResolver;

    @Mock
    private EventAdmin eventAdmin;

    private Map<String, Map<String, String>> aliasMap;
    private int testSize = 5;

    private int pageSize;
    private int prevPageSize = 1000;

    @Parameters(name = "page size -> {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][] { { 1 }, { 1000 } });
    }

    public MapEntriesTest(int pageSize) {
        this.pageSize = pageSize;
    }

    @Override
    @SuppressWarnings({ "unchecked" })
    @Before
    public void setup() throws Exception {
        prevPageSize = Integer.getInteger("sling.vanityPath.pageSize", 2000);
        System.setProperty("sling.vanityPath.pageSize", Integer.toString(pageSize));

        MockitoAnnotations.initMocks(this);

        final List<VanityPathConfig> configs = new ArrayList<>();
        configs.add(new VanityPathConfig("/libs/", false));
        configs.add(new VanityPathConfig("/libs/denied", true));
        configs.add(new VanityPathConfig("/foo/", false));
        configs.add(new VanityPathConfig("/baa/", false));
        configs.add(new VanityPathConfig("/justVanityPath", false));
        configs.add(new VanityPathConfig("/justVanityPath2", false));
        configs.add(new VanityPathConfig("/badVanityPath", false));
        configs.add(new VanityPathConfig("/redirectingVanityPath", false));
        configs.add(new VanityPathConfig("/redirectingVanityPath301", false));
        configs.add(new VanityPathConfig("/vanityPathOnJcrContent", false));

        Collections.sort(configs);
        when(bundle.getSymbolicName()).thenReturn("TESTBUNDLE");
        when(bundleContext.getBundle()).thenReturn(bundle);
        when(resourceResolverFactory.getServiceResourceResolver(any(Map.class))).thenReturn(resourceResolver);
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(true);
        when(resourceResolverFactory.getVanityPathConfig()).thenReturn(configs);
        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(true);
        when(resourceResolverFactory.getObservationPaths()).thenReturn(new Path[] {new Path("/")});
        when(resourceResolverFactory.getMapRoot()).thenReturn(MapEntries.DEFAULT_MAP_ROOT);
        when(resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(-1L);
        when(resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(true);
        when(resourceResolver.findResources(anyString(), eq("sql"))).thenReturn(
                Collections.<Resource> emptySet().iterator());
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenReturn(
                Collections.<Resource> emptySet().iterator());
        //when(resourceResolverFactory.getAliasPath()).thenReturn(Arrays.asList("/child"));

        Set<String> aliasPath = new TreeSet<>();
        aliasPath.add("/parent");
        for(int i = 1; i < testSize; i++){
          aliasPath.add("/parent"+i);
        }
        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(aliasPath);

        Optional<ResourceResolverMetrics> metrics = Optional.empty();

        mapEntries = Mockito.spy(new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics));
        final Field aliasMapField = MapEntries.class.getDeclaredField("aliasMap");
        aliasMapField.setAccessible(true);

        this.aliasMap = ( Map<String, Map<String, String>>) aliasMapField.get(mapEntries);
    }

    @Override
    @After
    public void tearDown() throws Exception {
        System.setProperty("sling.vanityPath.pageSize", Integer.toString(prevPageSize));
        mapEntries.dispose();
    }


    @Test
    public void test_simple_alias_support() {
        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        when(resourceResolver.findResources(anyString(), eq("sql"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains(ResourceResolverImpl.PROP_ALIAS)) {
                    return Collections.singleton(result).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        mapEntries.doInit();

        Map<String, String> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue(aliasMap.containsKey("alias"));
        assertEquals("child", aliasMap.get("alias"));
    }

    @Test
    public void test_that_duplicate_alias_doesnt_replace_first_alias() {
        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        final Resource secondResult = mock(Resource.class);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent/child2");
        when(secondResult.getName()).thenReturn("child2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        when(resourceResolver.findResources(anyString(), eq("sql"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains(ResourceResolverImpl.PROP_ALIAS)) {
                    return Arrays.asList(result, secondResult).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        mapEntries.doInit();

        Map<String, String> aliasMap = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMap);
        assertTrue(aliasMap.containsKey("alias"));
        assertEquals("child", aliasMap.get("alias"));
    }

    @Test
    public void test_vanity_path_registration() throws Exception {
        // specifically making this a weird value because we want to verify that
        // the configuration value is being used
        int DEFAULT_VANITY_STATUS = 333333;

        when(resourceResolverFactory.getDefaultVanityPathRedirectStatus()).thenReturn(DEFAULT_VANITY_STATUS);

        final List<Resource> resources = new ArrayList<>();

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));
        resources.add(justVanityPath);

        Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));
        resources.add(badVanityPath);

        Resource redirectingVanityPath = mock(Resource.class, "redirectingVanityPath");
        when(redirectingVanityPath.getPath()).thenReturn("/redirectingVanityPath");
        when(redirectingVanityPath.getName()).thenReturn("redirectingVanityPath");
        when(redirectingVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/redirectingVanityPath", "sling:redirect", true));
        resources.add(redirectingVanityPath);

        Resource redirectingVanityPath301 = mock(Resource.class, "redirectingVanityPath301");
        when(redirectingVanityPath301.getPath()).thenReturn("/redirectingVanityPath301");
        when(redirectingVanityPath301.getName()).thenReturn("redirectingVanityPath301");
        when(redirectingVanityPath301.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/redirectingVanityPath301", "sling:redirect", true, "sling:redirectStatus", 301));
        resources.add(redirectingVanityPath301);

        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));
        resources.add(vanityPathOnJcrContent);

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                String query = invocation.getArguments()[0].toString();
                if (matchesPagedQuery(query)) {
                    String path = extractStartPath(query);
                    Collections.sort(resources, vanityResourceComparator);
                    return resources.stream().filter(e -> getFirstVanityPath(e).compareTo(path) > 0).iterator();
                } else if (query.contains("sling:vanityPath")) {
                    return resources.iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        mapEntries.doInit();
        mapEntries.initializeVanityPaths();

        List<MapEntry> entries = mapEntries.getResolveMaps();

        assertEquals(8, entries.size());
        for (MapEntry entry : entries) {
            if (entry.getPattern().contains("/target/redirectingVanityPath301")) {
                assertEquals(301, entry.getStatus());
                assertFalse(entry.isInternal());
            } else if (entry.getPattern().contains("/target/redirectingVanityPath")) {
                assertEquals(DEFAULT_VANITY_STATUS, entry.getStatus());
                assertFalse(entry.isInternal());
            } else if (entry.getPattern().contains("/target/justVanityPath")) {
                assertTrue(entry.isInternal());
            } else if (entry.getPattern().contains("/target/vanityPathOnJcrContent")) {
                for (String redirect : entry.getRedirect()) {
                    assertFalse(redirect.contains("jcr:content"));
                }
            }
        }

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(4, vanityTargets.size());
    }

    @Test
    public void test_vanity_path_updates() throws Exception {
        Resource parent = mock(Resource.class, "parent");
        when(parent.getPath()).thenReturn("/foo/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(new ValueMapDecorator(Collections.<String, Object>emptyMap()));
        when(resourceResolver.getResource(parent.getPath())).thenReturn(parent);

        Resource child = mock(Resource.class, "jcrcontent");
        when(child.getPath()).thenReturn("/foo/parent/jcr:content");
        when(child.getName()).thenReturn("jcr:content");
        when(child.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found"));
        when(child.getParent()).thenReturn(parent);
        when(parent.getChild(child.getName())).thenReturn(child);
        when(resourceResolver.getResource(child.getPath())).thenReturn(child);

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                return Collections.<Resource> emptySet().iterator();
            }
        });

        mapEntries.doInit();
        mapEntries.initializeVanityPaths();

        // map entries should have no alias atm
        assertTrue( mapEntries.getResolveMaps().isEmpty());

        // add parent
        mapEntries.onChange(Arrays.asList(new ResourceChange(ChangeType.ADDED, parent.getPath(), false)));
        assertTrue( mapEntries.getResolveMaps().isEmpty());

        // add child
        mapEntries.onChange(Arrays.asList(new ResourceChange(ChangeType.ADDED, child.getPath(), false)));

        // two entries for the vanity path
        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());
        for (MapEntry entry : entries) {
            assertTrue(entry.getPattern().contains("/target/found"));
        }

        // update parent - no change
        mapEntries.onChange(Arrays.asList(new ResourceChange(ChangeType.CHANGED, parent.getPath(), false)));
        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());
        for (MapEntry entry : entries) {
            assertTrue(entry.getPattern().contains("/target/found"));
        }

        // update child - no change
        mapEntries.onChange(Arrays.asList(new ResourceChange(ChangeType.CHANGED, child.getPath(), false)));
        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());
        for (MapEntry entry : entries) {
            assertTrue(entry.getPattern().contains("/target/found"));
        }

        // remove child - empty again
        when(resourceResolver.getResource(child.getPath())).thenReturn(null);
        when(parent.getChild(child.getName())).thenReturn(null);
        mapEntries.onChange(Arrays.asList(new ResourceChange(ChangeType.REMOVED, child.getPath(), false)));
        assertTrue( mapEntries.getResolveMaps().isEmpty());

        // remove parent - still empty
        when(resourceResolver.getResource(parent.getPath())).thenReturn(null);
        mapEntries.onChange(Arrays.asList(new ResourceChange(ChangeType.REMOVED, parent.getPath(), false)));
        assertTrue( mapEntries.getResolveMaps().isEmpty());
    }
    
    @Test
    public void test_vanity_path_updates_do_not_reload_multiple_times() throws IOException {
        Resource parent = mock(Resource.class, "parent");
        when(parent.getPath()).thenReturn("/foo/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found1"));
        when(resourceResolver.getResource(parent.getPath())).thenReturn(parent);

        Resource child = mock(Resource.class, "jcrcontent");
        when(child.getPath()).thenReturn("/foo/parent/jcr:content");
        when(child.getName()).thenReturn("jcr:content");
        when(child.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found2"));
        when(child.getParent()).thenReturn(parent);
        when(parent.getChild(child.getName())).thenReturn(child);
        when(resourceResolver.getResource(child.getPath())).thenReturn(child);
        
        Resource child2 = mock(Resource.class, "child2");
        when(child2.getPath()).thenReturn("/foo/parent/child2");
        when(child2.getName()).thenReturn("child2");
        when(child2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/found3"));
        when(child2.getParent()).thenReturn(parent);
        when(parent.getChild(child2.getName())).thenReturn(child2);
        when(resourceResolver.getResource(child2.getPath())).thenReturn(child2);
        
        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                return Collections.<Resource> emptySet().iterator();
            }
        });
        
        mapEntries.doInit();
        mapEntries.initializeVanityPaths();

        // map entries should have no alias atm
        assertTrue( mapEntries.getResolveMaps().isEmpty());
        // till now we already have 2 events being sent
        Mockito.verify(eventAdmin,Mockito.times(2)).postEvent(Mockito.anyObject());

        // 3 updates at the same onChange call
        mapEntries.onChange(Arrays.asList(
                new ResourceChange(ChangeType.ADDED, parent.getPath(), false),
                new ResourceChange(ChangeType.ADDED, child.getPath(), false),
                new ResourceChange(ChangeType.ADDED, child2.getPath(), false)
                ));
        
        // 6 entries for the vanity path
        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(6, entries.size());
        
        assertTrue(entries.stream().anyMatch(e -> e.getPattern().contains("/target/found1")));
        assertTrue(entries.stream().anyMatch(e -> e.getPattern().contains("/target/found2")));
        assertTrue(entries.stream().anyMatch(e -> e.getPattern().contains("/target/found3")));
        
        // a single event is sent for all 3 added vanity paths
        Mockito.verify(eventAdmin,Mockito.times(3)).postEvent(Mockito.anyObject());
    }

    @Test
    public void test_vanity_path_registration_include_exclude() throws IOException {
        final String[] validPaths = {"/libs/somewhere", "/libs/a/b", "/foo/a", "/baa/a"};
        final String[] invalidPaths = {"/libs/denied/a", "/libs/denied/b/c", "/nowhere"};

        final List<Resource> resources = new ArrayList<>();
        for(final String val : validPaths) {
            resources.add(getVanityPathResource(val));
        }
        for(final String val : invalidPaths) {
            resources.add(getVanityPathResource(val));
        }


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                String query = invocation.getArguments()[0].toString();
                if (matchesPagedQuery(query)) {
                    String path = extractStartPath(query);
                    Collections.sort(resources, vanityResourceComparator);
                    return resources.stream().filter(e -> getFirstVanityPath(e).compareTo(path) > 0).iterator();
                } else
                if (query.contains("sling:vanityPath")) {
                    return resources.iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        mapEntries.doInit();
        mapEntries.initializeVanityPaths();

        List<MapEntry> entries = mapEntries.getResolveMaps();
        // each valid resource results in 2 entries
        assertEquals(validPaths.length * 2, entries.size());

        final Set<String> resultSet = new HashSet<>();
        for(final String p : validPaths) {
            resultSet.add(p + "$1");
            resultSet.add(p + ".html");
        }
        for (final MapEntry entry : entries) {
            assertTrue(resultSet.remove(entry.getRedirect()[0]));
        }
    }

    @Test
    public void test_getActualContentPath() throws Exception {

        Method method = MapEntries.class.getDeclaredMethod("getActualContentPath", String.class);
        method.setAccessible(true);

        String actualContent = (String) method.invoke(mapEntries, "/content");
        assertEquals("/content", actualContent);

        actualContent = (String) method.invoke(mapEntries, "/content/jcr:content");
        assertEquals("/content", actualContent);
    }

    @Test
    public void test_getMapEntryRedirect() throws Exception {

        Method method = MapEntries.class.getDeclaredMethod("getMapEntryRedirect", MapEntry.class);
        method.setAccessible(true);

        MapEntry mapEntry = new MapEntry("/content", -1, false, 0, "/content");
        String actualContent = (String) method.invoke(mapEntries, mapEntry);
        assertEquals("/content", actualContent);

        mapEntry = new MapEntry("/content", -1, false, 0, "/content$1");
        actualContent = (String) method.invoke(mapEntries, mapEntry);
        assertEquals("/content", actualContent);

        mapEntry = new MapEntry("/content", -1, false, 0, "/content.html");
        actualContent = (String) method.invoke(mapEntries, mapEntry);
        assertEquals("/content", actualContent);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_doAddVanity() throws Exception {
        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());
        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        //bad vanity
        Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(resourceResolver.getResource("/badVanityPath")).thenReturn(badVanityPath);
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));

        addResource.invoke(mapEntries, "/badVanityPath", new AtomicBoolean());


        assertEquals(2, entries.size());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(1, vanityTargets.size());

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(4, entries.size());

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(4, counter.longValue());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(2, vanityTargets.size());

        assertNull(vanityTargets.get("/vanityPathOnJcrContent/jcr:content"));
        assertNotNull(vanityTargets.get("/vanityPathOnJcrContent"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_doAddVanity_1() throws Exception {
        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(10L);

        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());
        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        //bad vanity
        Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(resourceResolver.getResource("/badVanityPath")).thenReturn(badVanityPath);
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));

        addResource.invoke(mapEntries, "/badVanityPath", new AtomicBoolean());


        assertEquals(2, entries.size());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(1, vanityTargets.size());

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        entries = mapEntries.getResolveMaps();
        assertEquals(4, entries.size());

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(4, counter.longValue());

        vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(2, vanityTargets.size());

        assertNull(vanityTargets.get("/vanityPathOnJcrContent/jcr:content"));
        assertNotNull(vanityTargets.get("/vanityPathOnJcrContent"));
    }


    @SuppressWarnings("unchecked")
    @Test
    public void test_doUpdateVanity() throws Exception {
        Field field0 = MapEntries.class.getDeclaredField("resolveMapsMap");
        field0.setAccessible(true);
        Map<String, List<MapEntry>> resolveMapsMap = (Map<String, List<MapEntry>>) field0.get(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method updateResource = MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        updateResource.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));
        assertNull(resolveMapsMap.get("/target/justVanityPathUpdated"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals("/target/justVanityPath", vanityTargets.get("/justVanityPath").get(0));

        //update vanity path
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPathUpdated"));
        updateResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/justVanityPath"));
        assertNotNull(resolveMapsMap.get("/target/justVanityPathUpdated"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals("/target/justVanityPathUpdated", vanityTargets.get("/justVanityPath").get(0));

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getValueMap()).thenReturn(buildValueMap());

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(3, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContentUpdated"));
        assertEquals(1, vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals("/target/vanityPathOnJcrContent", vanityTargets.get("/vanityPathOnJcrContent").get(0));

        //update vanity path
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContentUpdated"));
        updateResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(3, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContentUpdated"));
        assertEquals(1, vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals("/target/vanityPathOnJcrContentUpdated", vanityTargets.get("/vanityPathOnJcrContent").get(0));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void test_doRemoveVanity() throws Exception {
        Field field0 = MapEntries.class.getDeclaredField("resolveMapsMap");
        field0.setAccessible(true);
        Map<String, List<MapEntry>> resolveMapsMap = (Map<String, List<MapEntry>>) field0.get(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        Method method1 = MapEntries.class.getDeclaredMethod("doRemoveVanity", String.class);
        method1.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        addResource.invoke(mapEntries, "/justVanityPath", new AtomicBoolean());

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));
        assertEquals(1, vanityTargets.get("/justVanityPath").size());
        assertEquals("/target/justVanityPath", vanityTargets.get("/justVanityPath").get(0));

        //remove vanity path
        method1.invoke(mapEntries, "/justVanityPath");

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());

        assertEquals(1, resolveMapsMap.size());
        assertEquals(0, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/justVanityPath"));

        //vanity under jcr:content
        Resource vanityPathOnJcrContentParent = mock(Resource.class, "vanityPathOnJcrContentParent");
        when(vanityPathOnJcrContentParent.getPath()).thenReturn("/vanityPathOnJcrContent");
        when(vanityPathOnJcrContentParent.getName()).thenReturn("vanityPathOnJcrContent");

        Resource vanityPathOnJcrContent = mock(Resource.class, "vanityPathOnJcrContent");
        when(resourceResolver.getResource("/vanityPathOnJcrContent/jcr:content")).thenReturn(vanityPathOnJcrContent);
        when(vanityPathOnJcrContent.getPath()).thenReturn("/vanityPathOnJcrContent/jcr:content");
        when(vanityPathOnJcrContent.getName()).thenReturn("jcr:content");
        when(vanityPathOnJcrContent.getParent()).thenReturn(vanityPathOnJcrContentParent);
        when(vanityPathOnJcrContent.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/vanityPathOnJcrContent"));

        addResource.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content", new AtomicBoolean());

        assertEquals(2, resolveMapsMap.size());
        assertEquals(1, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));
        assertEquals(1,vanityTargets.get("/vanityPathOnJcrContent").size());
        assertEquals("/target/vanityPathOnJcrContent", vanityTargets.get("/vanityPathOnJcrContent").get(0));

        //remove vanity path
        method1.invoke(mapEntries, "/vanityPathOnJcrContent/jcr:content");

        assertEquals(1, resolveMapsMap.size());
        assertEquals(0, vanityTargets.size());
        assertNull(resolveMapsMap.get("/target/vanityPathOnJcrContent"));

    }
/*
    @SuppressWarnings("unchecked")
    @Test
    public void test_doUpdateVanityOrder() throws Exception {
        Field field0 = MapEntries.class.getDeclaredField("resolveMapsMap");
        field0.setAccessible(true);
        Map<String, List<MapEntry>> resolveMapsMap = (Map<String, List<MapEntry>>) field0.get(mapEntries);
        assertEquals(1, resolveMapsMap.size());

        Field field = MapEntries.class.getDeclaredField("vanityTargets");
        field.setAccessible(true);
        Map<String, List<String>> vanityTargets = (Map<String, List<String>>) field.get(mapEntries);
        assertEquals(0, vanityTargets.size());

        Method method = MapEntries.class.getDeclaredMethod("doAddVanity", String.class);
        method.setAccessible(true);

        Method method1 = MapEntries.class.getDeclaredMethod("doUpdateVanityOrder", String.class, boolean.class);
        method1.setAccessible(true);

        Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        method.invoke(mapEntries, "/justVanityPath");

        Resource justVanityPath2 = mock(Resource.class, "justVanityPath2");
        when(resourceResolver.getResource("/justVanityPath2")).thenReturn(justVanityPath2);
        when(justVanityPath2.getPath()).thenReturn("/justVanityPath2");
        when(justVanityPath2.getName()).thenReturn("justVanityPath2");
        when(justVanityPath2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 100));

        method.invoke(mapEntries, "/justVanityPath2");

        assertEquals(2, resolveMapsMap.size());
        assertEquals(2, vanityTargets.size());
        assertNotNull(resolveMapsMap.get("/target/justVanityPath"));

        Iterator <MapEntry> iterator = resolveMapsMap.get("/target/justVanityPath").iterator();
        assertEquals("/justVanityPath2$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2.html", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath.html", iterator.next().getRedirect()[0]);
        assertFalse(iterator.hasNext());

        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 1000));
        method1.invoke(mapEntries, "/justVanityPath",false);

        iterator = resolveMapsMap.get("/target/justVanityPath").iterator();
        assertEquals("/justVanityPath$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath.html", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2.html", iterator.next().getRedirect()[0]);
        assertFalse(iterator.hasNext());

        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));
        method1.invoke(mapEntries, "/justVanityPath",true);

        iterator = resolveMapsMap.get("/target/justVanityPath").iterator();
        assertEquals("/justVanityPath2$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath$1", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath2.html", iterator.next().getRedirect()[0]);
        assertEquals("/justVanityPath.html", iterator.next().getRedirect()[0]);
        assertFalse(iterator.hasNext());
    }
*/

    //SLING-3727
    @Test
    public void test_doAddAliasAttributesWithDisableAliasOptimization() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(false);
        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, String> aliasMap = mapEntries.getAliasMap("/parent");
        assertNull(aliasMap);
    }

    //SLING-3727
    @Test
    public void test_doUpdateAttributesWithDisableAliasOptimization() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(false);
        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, String> aliasMap = mapEntries.getAliasMap("/parent");
        assertNull(aliasMap);
    }

    //SLING-3727
    @Test
    public void test_doRemoveAttributessWithDisableAliasOptimization() throws Exception {
        final Method removeAlias = MapEntries.class.getDeclaredMethod("removeAlias", String.class, String.class, AtomicBoolean.class);
        removeAlias.setAccessible(true);

        when(resourceResolverFactory.isOptimizeAliasResolutionEnabled()).thenReturn(false);
        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        removeAlias.invoke(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        Map<String, String> aliasMap = mapEntries.getAliasMap("/parent");
        assertNull(aliasMap);
    }

    @Test
    public void test_doAddAlias() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("child", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        //test_that_duplicate_alias_doesnt_replace_first_alias
        final Resource secondResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2")).thenReturn(secondResult);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent/child2");
        when(secondResult.getName()).thenReturn("child2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent/child2", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("child", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        //testing jcr:content node
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));

        assertEquals(1, aliasMap.size());
    }

    @Test
    public void test_doAddAlias2() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent");
        when(result.getName()).thenReturn("parent");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent", new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("parent", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        //test_that_duplicate_alias_doesnt_replace_first_alias
        final Resource secondResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent2")).thenReturn(secondResult);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent2");
        when(secondResult.getName()).thenReturn("parent2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent2", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("parent", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        //testing jcr:content node
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));

        addResource.invoke(mapEntries, "/parent/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertEquals("parent", aliasMapEntry.get("aliasJcrContent"));

        assertEquals(1, aliasMap.size());

        //trying to add invalid alias path
        final Resource invalidResourcePath = mock(Resource.class);
        when(resourceResolver.getResource("/notallowedparent")).thenReturn(invalidResourcePath);
        when(invalidResourcePath.getParent()).thenReturn(parent);
        when(invalidResourcePath.getPath()).thenReturn("/notallowedparent");
        when(invalidResourcePath.getName()).thenReturn("notallowedparent");
        when(invalidResourcePath.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/notallowedparent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("parent", aliasMapEntry.get("alias"));
        assertEquals(1, aliasMap.size());

    }

    @Test
    public void test_doUpdateAlias() throws Exception {
        final Method updateResource = MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        updateResource.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        updateResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertFalse(aliasMapEntry.containsKey("aliasUpdated"));
        assertEquals("child", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasUpdated"));

        updateResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertFalse(aliasMapEntry.containsKey("alias"));
        assertTrue(aliasMapEntry.containsKey("aliasUpdated"));
        assertEquals("child", aliasMapEntry.get("aliasUpdated"));

        assertEquals(1, aliasMap.size());

        //testing jcr:content node update
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);

        updateResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertFalse(aliasMapEntry.containsKey("aliasJcrContentUpdated"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));

        assertEquals(1, aliasMap.size());

        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertFalse(aliasMapEntry.containsKey("aliasJcrContent"));
        assertTrue(aliasMapEntry.containsKey("aliasJcrContentUpdated"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContentUpdated"));

        assertEquals(1, aliasMap.size());

        //re-update alias
        updateResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertFalse(aliasMapEntry.containsKey("alias"));
        assertTrue(aliasMapEntry.containsKey("aliasUpdated"));
        assertEquals("child", aliasMapEntry.get("aliasUpdated"));

        //add another node with different alias and check that the update doesn't break anything (see also SLING-3728)
        final Resource secondResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2")).thenReturn(secondResult);
        when(secondResult.getParent()).thenReturn(parent);
        when(secondResult.getPath()).thenReturn("/parent/child2");
        when(secondResult.getName()).thenReturn("child2");
        when(secondResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias2"));

        updateResource.invoke(mapEntries, "/parent/child2", new AtomicBoolean());
        assertEquals(1, aliasMap.size());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(3, aliasMapEntry.size());

        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(3, aliasMapEntry.size());
        assertFalse(aliasMapEntry.containsKey("aliasJcrContent"));
        assertTrue(aliasMapEntry.containsKey("aliasJcrContentUpdated"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContentUpdated"));

        assertEquals(1, aliasMap.size());


        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, null));
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContentUpdated"));
        updateResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertFalse(aliasMapEntry.containsKey("aliasJcrContent"));
        assertTrue(aliasMapEntry.containsKey("aliasJcrContentUpdated"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContentUpdated"));

        assertEquals(1, aliasMap.size());

    }

    @Test
    public void test_doRemoveAlias() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method removeAlias = MapEntries.class.getDeclaredMethod("removeAlias", String.class, String.class, AtomicBoolean.class);
        removeAlias.setAccessible(true);

        // check that alias map is empty
        assertEquals(0, aliasMap.size());

        final Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource child = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(child);
        when(child.getParent()).thenReturn(parent);
        when(child.getPath()).thenReturn("/parent/child");
        when(child.getName()).thenReturn("child");
        when(child.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("child", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNull(aliasMapEntry);

        assertEquals(0, aliasMap.size());

        //re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent/child")).thenReturn(child);
        addResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("child", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNull(aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias2() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method removeAlias = MapEntries.class.getDeclaredMethod("removeAlias", String.class, String.class, AtomicBoolean.class);
        removeAlias.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent/child");
        when(result.getName()).thenReturn("child");
        when(result.getValueMap()).thenReturn(buildValueMap());

        //testing jcr:content node removal
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);

        addResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNull(aliasMapEntry);

        assertEquals(0, aliasMap.size());

        //re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);
        addResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));

        assertEquals(1, aliasMap.size());
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNull(aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias3() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method removeAlias = MapEntries.class.getDeclaredMethod("removeAlias", String.class, String.class, AtomicBoolean.class);
        removeAlias.setAccessible(true);

        assertEquals(0, aliasMap.size());

        final Resource parentRsrc = mock(Resource.class);
        when(parentRsrc.getPath()).thenReturn("/parent");

        final Resource childRsrc = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child")).thenReturn(childRsrc);
        when(childRsrc.getParent()).thenReturn(parentRsrc);
        when(childRsrc.getPath()).thenReturn("/parent/child");
        when(childRsrc.getName()).thenReturn("child");
        when(childRsrc.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(childRsrc);
        when(jcrContentResult.getPath()).thenReturn("/parent/child/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(childRsrc.getChild("jcr:content")).thenReturn(jcrContentResult);

        addResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        // test with two nodes
        assertEquals(1, aliasMap.size());
        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));
        assertEquals("child", aliasMapEntry.get("alias"));

        // remove child jcr:content node
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(childRsrc.getChild("jcr:content")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

        // test with one node
        assertEquals(1, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertEquals("child", aliasMapEntry.get("alias"));

        // re-add the node and test /parent/child
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(childRsrc.getChild("jcr:content")).thenReturn(jcrContentResult);
        addResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        // STOP
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(2, aliasMapEntry.size());
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));
        assertEquals("child", aliasMapEntry.get("alias"));

        when(resourceResolver.getResource("/parent/child")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child", new AtomicBoolean());
        when(resourceResolver.getResource("/parent/child")).thenReturn(childRsrc);
        addResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());

        assertEquals(1, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));

        // re-add the node and test node removal
        addResource.invoke(mapEntries, "/parent/child", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));
        assertEquals(2, aliasMapEntry.size());

        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(null);
        when(childRsrc.getChild("jcr:content")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child/jcr:content", new AtomicBoolean());

        assertEquals(1, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());

        // re-add the node and test node removal for  /parent/child
        when(resourceResolver.getResource("/parent/child/jcr:content")).thenReturn(jcrContentResult);
        when(childRsrc.getChild("jcr:content")).thenReturn(jcrContentResult);
        addResource.invoke(mapEntries, "/parent/child/jcr:content", new AtomicBoolean());


        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertEquals("child", aliasMapEntry.get("aliasJcrContent"));
        assertEquals(2, aliasMapEntry.size());

        when(resourceResolver.getResource("/parent/child")).thenReturn( null);
        removeAlias.invoke(mapEntries, "/parent", "/parent/child", new AtomicBoolean());

        assertEquals(0, aliasMap.size());
        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNull(aliasMapEntry);
    }

    @Test
    public void test_doRemoveAlias4() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method removeAlias = MapEntries.class.getDeclaredMethod("removeAlias", String.class, String.class, AtomicBoolean.class);
        removeAlias.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent");
        when(result.getName()).thenReturn("parent");
        when(result.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "alias"));

        addResource.invoke(mapEntries, "/parent", new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("parent", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/", "/parent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNull(aliasMapEntry);

        assertEquals(0, aliasMap.size());

        //re-add node and test nodeDeletion true
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        addResource.invoke(mapEntries, "/parent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("alias"));
        assertEquals("parent", aliasMapEntry.get("alias"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/", "/parent", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNull(aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    @Test
    public void test_doRemoveAlias5() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method removeAlias = MapEntries.class.getDeclaredMethod("removeAlias", String.class, String.class, AtomicBoolean.class);
        removeAlias.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/");

        final Resource result = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(result);
        when(result.getParent()).thenReturn(parent);
        when(result.getPath()).thenReturn("/parent");
        when(result.getName()).thenReturn("parent");
        when(result.getValueMap()).thenReturn(buildValueMap());

        //testing jcr:content node removal
        final Resource jcrContentResult = mock(Resource.class);
        when(resourceResolver.getResource("/parent/jcr:content")).thenReturn(jcrContentResult);
        when(jcrContentResult.getParent()).thenReturn(result);
        when(jcrContentResult.getPath()).thenReturn("/parent/jcr:content");
        when(jcrContentResult.getName()).thenReturn("jcr:content");
        when(jcrContentResult.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "aliasJcrContent"));
        when(result.getChild("jcr:content")).thenReturn(jcrContentResult);

        addResource.invoke(mapEntries, "/parent/jcr:content", new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("aliasJcrContent"));
        assertEquals("parent", aliasMapEntry.get("aliasJcrContent"));

        assertEquals(1, aliasMap.size());

        when(resourceResolver.getResource("/parent/jcr:content")).thenReturn(null);
        when(result.getChild("jcr:content")).thenReturn(null);
        removeAlias.invoke(mapEntries, "/", "/parent/jcr:content", new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/");
        assertNull(aliasMapEntry);

        assertEquals(0, aliasMap.size());
    }

    // SLING-10476
    @Test
    public void test_doNotRemoveAliasWhenJCRContentDeletedInParentPath() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method removeResource = MapEntries.class.getDeclaredMethod("removeResource", String.class, AtomicBoolean.class);
        removeResource.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(resourceResolver.getResource("/parent")).thenReturn(parent);
        when(parent.getParent()).thenReturn(parent);
        when(parent.getPath()).thenReturn("/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(buildValueMap());

        final Resource container = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container")).thenReturn(container);
        when(container.getParent()).thenReturn(parent);
        when(container.getPath()).thenReturn("/parent/container");
        when(container.getName()).thenReturn("container");
        when(container.getValueMap()).thenReturn(buildValueMap());
        when(parent.getChild("container")).thenReturn(container);

        final Resource jcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/jcr:content")).thenReturn(jcrContent);
        when(jcrContent.getParent()).thenReturn(container);
        when(jcrContent.getPath()).thenReturn("/parent/container/jcr:content");
        when(jcrContent.getName()).thenReturn("jcr:content");
        when(jcrContent.getValueMap()).thenReturn(buildValueMap());
        when(container.getChild("jcr:content")).thenReturn(jcrContent);

        final Resource childContainer = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/childContainer")).thenReturn(childContainer);
        when(childContainer.getParent()).thenReturn(container);
        when(childContainer.getPath()).thenReturn("/parent/container/childContainer");
        when(childContainer.getName()).thenReturn("childContainer");
        when(childContainer.getValueMap()).thenReturn(buildValueMap());
        when(container.getChild("childContainer")).thenReturn(childContainer);

        final Resource grandChild = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/childContainer/grandChild")).thenReturn(grandChild);
        when(grandChild.getParent()).thenReturn(childContainer);
        when(grandChild.getPath()).thenReturn("/parent/container/childContainer/grandChild");
        when(grandChild.getName()).thenReturn("grandChild");
        when(grandChild.getValueMap()).thenReturn(buildValueMap());
        when(childContainer.getChild("grandChild")).thenReturn(grandChild);

        final Resource grandChildJcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/container/childContainer/grandChild/jcr:content")).thenReturn(grandChildJcrContent);
        when(grandChildJcrContent.getParent()).thenReturn(grandChild);
        when(grandChildJcrContent.getPath()).thenReturn("/parent/container/childContainer/grandChild/jcr:content");
        when(grandChildJcrContent.getName()).thenReturn("jcr:content");
        when(grandChildJcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "gc"));
        when(grandChild.getChild("jcr:content")).thenReturn(grandChildJcrContent);

        addResource.invoke(mapEntries, grandChildJcrContent.getPath(), new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/parent/container/childContainer");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("gc"));
        assertEquals("grandChild", aliasMapEntry.get("gc"));


        // delete the jcr:content present in a parent path
        when(container.getChild("jcr:content")).thenReturn(null);
        removeResource.invoke(mapEntries, jcrContent.getPath(), new AtomicBoolean());

        // Alias of the other resources under the same parent of deleted jcr:content, should not be deleted
        aliasMapEntry = mapEntries.getAliasMap("/parent/container/childContainer");
        assertNotNull(aliasMapEntry);
        assertEquals(1, aliasMapEntry.size());
        assertTrue(aliasMapEntry.containsKey("gc"));
        assertEquals("grandChild", aliasMapEntry.get("gc"));

    }

    @Test
    public void test_doRemoveAliasFromSibling() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);

        final Method removeResource = MapEntries.class.getDeclaredMethod("removeResource", String.class, AtomicBoolean.class);
        removeResource.setAccessible(true);

        assertEquals(0, aliasMap.size());

        Resource parent = mock(Resource.class);
        when(parent.getPath()).thenReturn("/parent");

        when(resourceResolver.getResource("/parent")).thenReturn(parent);
        when(parent.getParent()).thenReturn(parent);
        when(parent.getPath()).thenReturn("/parent");
        when(parent.getName()).thenReturn("parent");
        when(parent.getValueMap()).thenReturn(buildValueMap());

        final Resource child1 = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child1")).thenReturn(child1);
        when(child1.getParent()).thenReturn(parent);
        when(child1.getPath()).thenReturn("/parent/child1");
        when(child1.getName()).thenReturn("child1");
        when(child1.getValueMap()).thenReturn(buildValueMap());

        final Resource child1JcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child1/jcr:content")).thenReturn(child1JcrContent);
        when(child1JcrContent.getParent()).thenReturn(child1);
        when(child1JcrContent.getPath()).thenReturn("/parent/child1/jcr:content");
        when(child1JcrContent.getName()).thenReturn("jcr:content");
        when(child1JcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "test1"));
        when(child1.getChild("jcr:content")).thenReturn(child1JcrContent);

        when(parent.getChild("child1")).thenReturn(child1);

        addResource.invoke(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());

        Map<String, String> aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("test1"));
        assertEquals("child1", aliasMapEntry.get("test1"));

        assertEquals(1, aliasMap.size());

        final Resource child2 = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2")).thenReturn(child2);
        when(child2.getParent()).thenReturn(parent);
        when(child2.getPath()).thenReturn("/parent/child2");
        when(child2.getName()).thenReturn("child2");
        when(child2.getValueMap()).thenReturn(buildValueMap());

        final Resource child2JcrContent = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2/jcr:content")).thenReturn(child2JcrContent);
        when(child2JcrContent.getParent()).thenReturn(child2);
        when(child2JcrContent.getPath()).thenReturn("/parent/child2/jcr:content");
        when(child2JcrContent.getName()).thenReturn("jcr:content");
        when(child2JcrContent.getValueMap()).thenReturn(buildValueMap(ResourceResolverImpl.PROP_ALIAS, "test2"));
        when(child2.getChild("jcr:content")).thenReturn(child2JcrContent);

        when(parent.getChild("child2")).thenReturn(child2);

        addResource.invoke(mapEntries, child2JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("test1"));
        assertTrue(aliasMapEntry.containsKey("test2"));
        assertEquals("child1", aliasMapEntry.get("test1"));
        assertEquals("child2", aliasMapEntry.get("test2"));


        assertEquals(1, aliasMap.size());
        assertEquals(2, mapEntries.getAliasMap("/parent").size());

        final Resource child2JcrContentChild = mock(Resource.class);
        when(resourceResolver.getResource("/parent/child2/jcr:content/test")).thenReturn(child2JcrContentChild);
        when(child2JcrContentChild.getParent()).thenReturn(child2);
        when(child2JcrContentChild.getPath()).thenReturn("/parent/child2/jcr:content/test");
        when(child2JcrContentChild.getName()).thenReturn("test");
        when(child2JcrContent.getChild("test")).thenReturn(child2JcrContentChild);

        removeResource.invoke(mapEntries, child2JcrContentChild.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("test1"));
        assertTrue(aliasMapEntry.containsKey("test2"));
        assertEquals("child1", aliasMapEntry.get("test1"));
        assertEquals("child2", aliasMapEntry.get("test2"));


        assertEquals(1, aliasMap.size());
        assertEquals(2, mapEntries.getAliasMap("/parent").size());

        when(child2.getChild("jcr:content")).thenReturn(null);

        removeResource.invoke(mapEntries, child2JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("test1"));
        assertEquals("child1", aliasMapEntry.get("test1"));


        assertEquals(1, aliasMap.size());
        assertEquals(1, mapEntries.getAliasMap("/parent").size());

        when(child1.getChild("jcr:content")).thenReturn(null);

        removeResource.invoke(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNull(aliasMapEntry);

        when(child1.getChild("jcr:content")).thenReturn(child1JcrContent);
        addResource.invoke(mapEntries, child1JcrContent.getPath(), new AtomicBoolean());
        when(child2.getChild("jcr:content")).thenReturn(child2JcrContent);
        addResource.invoke(mapEntries, child2JcrContent.getPath(), new AtomicBoolean());

        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNotNull(aliasMapEntry);
        assertTrue(aliasMapEntry.containsKey("test1"));
        assertTrue(aliasMapEntry.containsKey("test2"));
        assertEquals("child1", aliasMapEntry.get("test1"));
        assertEquals("child2", aliasMapEntry.get("test2"));


        assertEquals(1, aliasMap.size());
        assertEquals(2, mapEntries.getAliasMap("/parent").size());

        removeResource.invoke(mapEntries, parent.getPath(), new AtomicBoolean());


        aliasMapEntry = mapEntries.getAliasMap("/parent");
        assertNull(aliasMapEntry);
    }

    @Test
    public void test_isValidVanityPath() throws Exception {
        Method method = MapEntries.class.getDeclaredMethod("isValidVanityPath", String.class);
        method.setAccessible(true);

        assertFalse((Boolean)method.invoke(mapEntries, "/jcr:system/node"));

        assertTrue((Boolean)method.invoke(mapEntries, "/justVanityPath"));
    }

    @Test
    //SLING-4847
    public void test_doNodeAdded1() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);
        final AtomicBoolean refreshed = new AtomicBoolean(false);
        addResource.invoke(mapEntries, "/node", refreshed);
        assertTrue(refreshed.get());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_1() throws Exception {

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);

        Method method = MapEntries.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries, "/notExisting");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_2() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);

        Method method = MapEntries.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries, "/target/justVanityPath");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        final Resource justVanityPath2 = mock(Resource.class, "justVanityPath2");
        when(resourceResolver.getResource("/justVanityPath2")).thenReturn(justVanityPath2);
        when(justVanityPath2.getPath()).thenReturn("/justVanityPath2");
        when(justVanityPath2.getName()).thenReturn("justVanityPath2");
        when(justVanityPath2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 100));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        method.invoke(mapEntries, "/target/justVanityPath");

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(4, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_3() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(false);

        Method method = MapEntries.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries, "/target/justVanityPath");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_4() throws Exception {

        final Resource badVanityPath = mock(Resource.class, "badVanityPath");
        when(badVanityPath.getPath()).thenReturn("/badVanityPath");
        when(badVanityPath.getName()).thenReturn("badVanityPath");
        when(badVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/content/mypage/en-us-{132"));


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(badVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(0L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(true);

        Method method = MapEntries.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries, "/content/mypage/en-us-{132");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(0, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getVanityPaths_5() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);
        when(this.resourceResolverFactory.isMaxCachedVanityPathEntriesStartup()).thenReturn(false);

        Method method = MapEntries.class.getDeclaredMethod("getVanityPaths", String.class);
        method.setAccessible(true);
        method.invoke(mapEntries, "/target/justVanityPath");

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        final Resource justVanityPath2 = mock(Resource.class, "justVanityPath2");
        when(resourceResolver.getResource("/justVanityPath2")).thenReturn(justVanityPath2);
        when(justVanityPath2.getPath()).thenReturn("/justVanityPath2");
        when(justVanityPath2.getName()).thenReturn("justVanityPath2");
        when(justVanityPath2.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath","sling:vanityOrder", 100));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        method.invoke(mapEntries, "/target/justVanityPath");

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_loadVanityPaths() throws Exception {
        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        Method method = MapEntries.class.getDeclaredMethod("loadVanityPaths", ResourceResolver.class);
        method.setAccessible(true);
        method.invoke(mapEntries, resourceResolver);

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_loadVanityPaths_1() throws Exception {

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });

        Method method = MapEntries.class.getDeclaredMethod("loadVanityPaths", ResourceResolver.class);
        method.setAccessible(true);
        method.invoke(mapEntries, resourceResolver);

        Field vanityCounter = MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
    }

    @Test
    //SLING-4891
    public void test_getMapEntryList() throws Exception {

        List<MapEntry> entries = mapEntries.getResolveMaps();
        assertEquals(0, entries.size());


        final Resource justVanityPath = mock(Resource.class,
                "justVanityPath");

        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);

        when(justVanityPath.getPath()).thenReturn("/justVanityPath");

        when(justVanityPath.getName()).thenReturn("justVanityPath");

        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath",
                "/target/justVanityPath"));

        when(resourceResolver.findResources(anyString(),
                eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

                    @Override
                    public Iterator<Resource> answer(InvocationOnMock invocation)
                            throws Throwable {
                        if
                        (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                            return Collections.singleton(justVanityPath).iterator();
                        } else {
                            return Collections.<Resource> emptySet().iterator();
                        }
                    }
                });

        Method method =
                MapEntries.class.getDeclaredMethod("getMapEntryList",String.class);
        method.setAccessible(true);
        method.invoke(mapEntries, "/target/justVanityPath");

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        Field vanityCounter =
                MapEntries.class.getDeclaredField("vanityCounter");
        vanityCounter.setAccessible(true);
        AtomicLong counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());

        method.invoke(mapEntries, "/target/justVanityPath");

        entries = mapEntries.getResolveMaps();
        assertEquals(2, entries.size());

        counter = (AtomicLong) vanityCounter.get(mapEntries);
        assertEquals(2, counter.longValue());
    }

    @Test
    //SLING-4883
    public void test_concutrrent_getResolveMapsIterator() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);

        final Resource justVanityPath = mock(Resource.class, "justVanityPath");
        when(resourceResolver.getResource("/justVanityPath")).thenReturn(justVanityPath);
        when(justVanityPath.getPath()).thenReturn("/justVanityPath");
        when(justVanityPath.getName()).thenReturn("justVanityPath");
        when(justVanityPath.getValueMap()).thenReturn(buildValueMap("sling:vanityPath", "/target/justVanityPath"));


        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {

            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                if (invocation.getArguments()[0].toString().contains("sling:vanityPath")) {
                    return Collections.singleton(justVanityPath).iterator();
                } else {
                    return Collections.<Resource> emptySet().iterator();
                }
            }
        });


        when(this.resourceResolverFactory.getMaxCachedVanityPathEntries()).thenReturn(2L);

        ArrayList<DataFuture> list = new ArrayList<>();
        for (int i =0;i<10;i++) {
            list.add(createDataFuture(pool, mapEntries));

        }

       for (DataFuture df : list) {
           df.future.get();
        }

    }

    // tests SLING-6542
    @Test
    public void sessionConcurrency() throws Exception {
        final Method addResource = MapEntries.class.getDeclaredMethod("addResource", String.class, AtomicBoolean.class);
        addResource.setAccessible(true);
        final Method updateResource = MapEntries.class.getDeclaredMethod("updateResource", String.class, AtomicBoolean.class);
        updateResource.setAccessible(true);
        final Method handleConfigurationUpdate = MapEntries.class.getDeclaredMethod("handleConfigurationUpdate",
                String.class, AtomicBoolean.class, AtomicBoolean.class, boolean.class);
        handleConfigurationUpdate.setAccessible(true);

        final Semaphore sessionLock = new Semaphore(1);
        // simulate somewhat slow (1ms) session operations that use locking
        // to determine that they are using the session exclusively.
        // if that lock mechanism detects concurrent access we fail
        Mockito.doAnswer(new Answer<Void>() {

            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                simulateSomewhatSlowSessionOperation(sessionLock);
                return null;
            }

        }).when(resourceResolver).refresh();
        Mockito.doAnswer(new Answer<Resource>() {

            @Override
            public Resource answer(InvocationOnMock invocation) throws Throwable {
                simulateSomewhatSlowSessionOperation(sessionLock);
                return null;
            }

        }).when(resourceResolver).getResource(any(String.class));

        when(resourceResolverFactory.isMapConfiguration(any(String.class))).thenReturn(true);

        final AtomicInteger failureCnt = new AtomicInteger(0);
        final List<Exception> exceptions = new LinkedList<>();
        final Semaphore done = new Semaphore(0);
        final int NUM_THREADS = 30;
        final Random random = new Random(12321);
        for(int i=0; i<NUM_THREADS; i++) {
            final int randomWait = random.nextInt(10);
            Runnable r = new Runnable() {

                @Override
                public void run() {
                    try{
                        Thread.sleep(randomWait);
                        for(int i=0; i<3; i++) {
                            addResource.invoke(mapEntries, "/node", new AtomicBoolean());
                            updateResource.invoke(mapEntries, "/node", new AtomicBoolean());
                            handleConfigurationUpdate.invoke(mapEntries, "/node", new AtomicBoolean(), new AtomicBoolean(), false);
                        }
                    } catch(Exception e) {
                        e.printStackTrace();
                        synchronized(exceptions) {
                            exceptions.add(e);
                        }
                        failureCnt.incrementAndGet();
                    } finally {
                        done.release();
                    }
                }
            };
            Thread th = new Thread(r);
            th.setDaemon(true);
            th.start();
        }
        assertTrue("threads did not finish in time", done.tryAcquire(NUM_THREADS, 30, TimeUnit.SECONDS));
        if (failureCnt.get() != 0) {
            synchronized(exceptions) {
                throw new AssertionError("exceptions encountered (" + failureCnt.get() + "). First exception: ", exceptions.get(0));
            }
        }
    }

    @Test
    public void testLoadAliases_ValidAbsolutePath_DefaultPaths() {
        when(resourceResolverFactory.getAllowedAliasLocations()).thenReturn(Collections.emptySet());

        when(resourceResolver.findResources(anyString(), eq("JCR-SQL2"))).thenAnswer(new Answer<Iterator<Resource>>() {
            @Override
            public Iterator<Resource> answer(InvocationOnMock invocation) throws Throwable {
                String query = StringUtils.trim((String)invocation.getArguments()[0]);
                assertEquals("SELECT sling:alias FROM nt:base AS page WHERE (NOT ISDESCENDANTNODE(page,'/jcr:system')) AND sling:alias IS NOT NULL", query);
                return Collections.<Resource> emptySet().iterator();
            }
        });

        mapEntries.doInit();
    }

    @Test
    public void test_vanitypath_disabled() throws Exception {
        // initialize with having vanity path disabled - must not throw errors here or on disposal
        when(resourceResolverFactory.isVanityPathEnabled()).thenReturn(false);

        mapEntries = new MapEntries(resourceResolverFactory, bundleContext, eventAdmin, stringInterpolationProvider, metrics);

        mapEntries.doInit();
    }

    // utilities for testing vanity path queries

    private static String VPQSTART = "SELECT [sling:vanityPath], [sling:redirect], [sling:redirectStatus] FROM [nt:base] WHERE NOT isdescendantnode('/jcr:system') AND [sling:vanityPath] IS NOT NULL AND FIRST([sling:vanityPath]) > '";
    private static String VPQEND = "' ORDER BY FIRST([sling:vanityPath])";

    private boolean matchesPagedQuery(String query) {
        return query.startsWith(VPQSTART) && query.endsWith(VPQEND);
    }

    private String extractStartPath(String query) {
        String remainder = query.substring(VPQSTART.length());
        return remainder.substring(0, remainder.length() - VPQEND.length());
    }

    private String getFirstVanityPath(Resource r) {
        String vp[] = r.getValueMap().get("sling:vanityPath", new String[0]);
        return vp.length == 0 ? "": vp[0];
    }

    private Comparator<Resource> vanityResourceComparator = new Comparator<Resource>() {
        @Override
        public int compare(Resource o1, Resource o2) {
            String s1 = getFirstVanityPath(o1);
            String s2 = getFirstVanityPath(o2);
            return s1.compareTo(s2);
        }
    };
}