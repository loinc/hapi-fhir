package ca.uhn.fhir.jpa.searchparam.registry;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeSearchParam;
import ca.uhn.fhir.interceptor.api.IInterceptorService;
import ca.uhn.fhir.jpa.cache.IResourceChangeListener;
import ca.uhn.fhir.jpa.cache.IResourceChangeListenerRegistry;
import ca.uhn.fhir.jpa.cache.IResourceVersionSvc;
import ca.uhn.fhir.jpa.cache.RegisteredResourceChangeListener;
import ca.uhn.fhir.jpa.cache.RegisteredResourceListenerFactory;
import ca.uhn.fhir.jpa.cache.ResourceChangeListenerCache;
import ca.uhn.fhir.jpa.cache.ResourceChangeListenerCacheRefresher;
import ca.uhn.fhir.jpa.cache.ResourceChangeListenerRegistryImpl;
import ca.uhn.fhir.jpa.cache.ResourceChangeResult;
import ca.uhn.fhir.jpa.cache.ResourceVersionMap;
import ca.uhn.fhir.jpa.model.entity.ModelConfig;
import ca.uhn.fhir.jpa.model.entity.ResourceTable;
import ca.uhn.fhir.jpa.model.sched.ISchedulerService;
import ca.uhn.fhir.jpa.searchparam.MatchUrlService;
import ca.uhn.fhir.jpa.searchparam.SearchParameterMap;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryMatchResult;
import ca.uhn.fhir.jpa.searchparam.matcher.InMemoryResourceMatcher;
import ca.uhn.fhir.jpa.searchparam.matcher.SearchParamMatcher;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import org.hl7.fhir.instance.model.api.IPrimitiveType;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.SearchParameter;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
public class SearchParamRegistryImplTest {
	private static final FhirContext ourFhirContext = FhirContext.forR4();
	private static final ReadOnlySearchParamCache ourBuiltInSearchParams = ReadOnlySearchParamCache.fromFhirContext(ourFhirContext);

	public static final int TEST_SEARCH_PARAMS = 3;
	private static List<ResourceTable> ourEntities;
	private static ResourceVersionMap ourResourceVersionMap;
	private static int ourLastId;
	private static int ourBuiltinPatientSearchParamCount;

	static {
		ourEntities = new ArrayList<>();
		for (ourLastId = 0; ourLastId < TEST_SEARCH_PARAMS; ++ourLastId) {
			ourEntities.add(createEntity(ourLastId, 1));
		}
		ourResourceVersionMap = ResourceVersionMap.fromResourceTableEntities(ourEntities);
		ourBuiltinPatientSearchParamCount = ReadOnlySearchParamCache.fromFhirContext(ourFhirContext).getSearchParamMap("Patient").size();
	}

	@Autowired
	SearchParamRegistryImpl mySearchParamRegistry;
	@Autowired
	private ResourceChangeListenerRegistryImpl myResourceChangeListenerRegistry;
	@Autowired
	private ResourceChangeListenerCacheRefresher myChangeListenerCacheRefresher;
	@Autowired
	private ResourceChangeListenerCache myResourceChangeListenerCache;

	@MockBean
	private IResourceVersionSvc myResourceVersionSvc;
	@MockBean
	private ISchedulerService mySchedulerService;
	@MockBean
	private ISearchParamProvider mySearchParamProvider;
	@Autowired
	private ModelConfig myModelConfig;
	@MockBean
	private IInterceptorService myInterceptorBroadcaster;
	@MockBean
	private SearchParamMatcher mySearchParamMatcher;
	@MockBean
	private MatchUrlService myMatchUrlService;

	@Configuration
	static class SpringConfig {
		@Bean
		FhirContext fhirContext() {
			return ourFhirContext;
		}

		@Bean
		ModelConfig modelConfig() {
			ModelConfig modelConfig = new ModelConfig();
			modelConfig.setDefaultSearchParamsCanBeOverridden(true);
			return modelConfig;
		}

		@Bean
		ISearchParamRegistry searchParamRegistry() {
			return new SearchParamRegistryImpl();
		}

		@Bean
		SearchParameterCanonicalizer searchParameterCanonicalizer(FhirContext theFhirContext) {
			return new SearchParameterCanonicalizer(theFhirContext);
		}

		@Bean
		IResourceChangeListenerRegistry resourceChangeListenerRegistry() {
			return new ResourceChangeListenerRegistryImpl();
		}

		@Bean
		ResourceChangeListenerCache resourceChangeListenerCache() {
			return new ResourceChangeListenerCache();
		}

		@Bean
		ResourceChangeListenerCacheRefresher resourceChangeListenerCacheRefresher() {
			return new ResourceChangeListenerCacheRefresher();
		}

		// FIXME KHS group these in a config.  they are used in a lot of tests
		@Bean
		RegisteredResourceListenerFactory myRegisteredResourceListenerFactory() {
			return new RegisteredResourceListenerFactory();
		}

		@Bean
		@Scope("prototype")
		RegisteredResourceChangeListener registeredResourceChangeListener(String theResourceName, IResourceChangeListener theResourceChangeListener, SearchParameterMap theSearchParameterMap, long theRemoteRefreshIntervalMs) {
			return new RegisteredResourceChangeListener(theResourceName, theResourceChangeListener, theSearchParameterMap, theRemoteRefreshIntervalMs);
		}

		@Bean
		InMemoryResourceMatcher inMemoryResourceMatcher() {
			InMemoryResourceMatcher retval = mock(InMemoryResourceMatcher.class);
			when(retval.checkIfInMemorySupported(any(), any())).thenReturn(InMemoryMatchResult.successfulMatch());
			return retval;
		}

	}

	@Nonnull
	private static ResourceTable createEntity(long theId, int theVersion) {
		ResourceTable searchParamEntity = new ResourceTable();
		searchParamEntity.setResourceType("SearchParameter");
		searchParamEntity.setId(theId);
		searchParamEntity.setVersion(theVersion);
		return searchParamEntity;
	}

	private int myAnswerCount = 0;

	@BeforeEach
	public void before() {
		myAnswerCount = 0;
		when(myResourceVersionSvc.getVersionMap(anyString(), any())).thenReturn(ourResourceVersionMap);
		when(mySearchParamProvider.search(any())).thenReturn(new SimpleBundleProvider());

		// Our first refresh adds our test searchparams to the registry
		assertResult(mySearchParamRegistry.refreshCacheIfNecessary(), TEST_SEARCH_PARAMS, 0, 0);
		assertEquals(TEST_SEARCH_PARAMS, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertDbCalled();
		assertEquals(ourBuiltInSearchParams.size(), mySearchParamRegistry.getActiveSearchParams().size());
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount);
	}

	@AfterEach
	public void after() {
		myResourceChangeListenerCache.clearCachesForUnitTest();
		// Empty out the searchparam registry
		mySearchParamRegistry.resetForUnitTest();
	}

	@Test
	public void testRefreshAfterExpiry() {
		mySearchParamRegistry.requestRefresh();
		// Second time we don't need to run because we ran recently
		assertEmptyResult(mySearchParamRegistry.refreshCacheIfNecessary());
	}

	@Test
	public void testRefreshCacheIfNecessary() {
		// Second refresh does not call the database
		assertEmptyResult(mySearchParamRegistry.refreshCacheIfNecessary());
		assertEquals(TEST_SEARCH_PARAMS, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertDbNotCalled();
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount);

		// Requesting a refresh calls the database and adds nothing
		mySearchParamRegistry.requestRefresh();
		assertEmptyResult(mySearchParamRegistry.refreshCacheIfNecessary());
		assertEquals(TEST_SEARCH_PARAMS, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertDbCalled();
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount);

		// Requesting a refresh after adding a new search parameter calls the database and adds one
		resetDatabaseToOrigSearchParamsPlusNewOneWithStatus(Enumerations.PublicationStatus.ACTIVE);
		mySearchParamRegistry.requestRefresh();
		assertResult(mySearchParamRegistry.refreshCacheIfNecessary(), 1, 0, 0);
		assertEquals(TEST_SEARCH_PARAMS + 1, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertDbCalled();
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount + 1);

		// Requesting a refresh after adding a new search parameter calls the database and
		// removes the one added above and adds this new one
		resetDatabaseToOrigSearchParamsPlusNewOneWithStatus(Enumerations.PublicationStatus.ACTIVE);
		mySearchParamRegistry.requestRefresh();
		assertResult(mySearchParamRegistry.refreshCacheIfNecessary(), 1, 0, 1);
		assertEquals(TEST_SEARCH_PARAMS + 1, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertDbCalled();
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount + 1);

		// Requesting a refresh after adding a new search parameter calls the database,
		// removes the ACTIVE one and adds the new one because this is a mock test
		resetDatabaseToOrigSearchParamsPlusNewOneWithStatus(Enumerations.PublicationStatus.DRAFT);
		mySearchParamRegistry.requestRefresh();
		assertEquals(TEST_SEARCH_PARAMS + 1, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertResult(mySearchParamRegistry.refreshCacheIfNecessary(), 1, 0, 1);
		assertDbCalled();
		// the new one does not appear in our patient search params because it's DRAFT
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount);
	}

	@Test
	public void testSearchParamUpdate() {
		// Requesting a refresh after adding a new search parameter calls the database and adds one
		List<ResourceTable> newEntities = resetDatabaseToOrigSearchParamsPlusNewOneWithStatus(Enumerations.PublicationStatus.ACTIVE);
		mySearchParamRegistry.requestRefresh();
		assertResult(mySearchParamRegistry.refreshCacheIfNecessary(), 1, 0, 0);
		assertEquals(TEST_SEARCH_PARAMS + 1, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertDbCalled();
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount + 1);

		// Update the resource without changing anything that would affect our cache
		ResourceTable lastEntity = newEntities.get(newEntities.size() - 1);
		lastEntity.setVersion(2);
		resetMock(Enumerations.PublicationStatus.ACTIVE, newEntities);
		mySearchParamRegistry.requestRefresh();
		assertResult(mySearchParamRegistry.refreshCacheIfNecessary(), 0, 1, 0);
		assertEquals(TEST_SEARCH_PARAMS + 1, myResourceChangeListenerCache.getResourceVersionCacheSizeForUnitTest());
		assertDbCalled();
		assertPatientSearchParamSize(ourBuiltinPatientSearchParamCount + 1);
	}

	private void assertPatientSearchParamSize(int theExpectedSize) {
		assertEquals(theExpectedSize, mySearchParamRegistry.getActiveSearchParams("Patient").size());
	}

	private void assertResult(ResourceChangeResult theResult, long theExpectedAdded, long theExpectedUpdated, long theExpectedRemoved) {
		assertEquals(theExpectedAdded, theResult.created, "added results");
		assertEquals(theExpectedUpdated, theResult.updated, "updated results");
		assertEquals(theExpectedRemoved, theResult.deleted, "removed results");
	}

	private void assertEmptyResult(ResourceChangeResult theResult) {
		assertResult(theResult, 0, 0, 0);
	}

	private void assertDbCalled() {
		verify(myResourceVersionSvc, times(1)).getVersionMap(anyString(), any());
		reset(myResourceVersionSvc);
		when(myResourceVersionSvc.getVersionMap(anyString(), any())).thenReturn(ourResourceVersionMap);
	}

	private void assertDbNotCalled() {
		verify(myResourceVersionSvc, never()).getVersionMap(anyString(), any());
		reset(myResourceVersionSvc);
		when(myResourceVersionSvc.getVersionMap(anyString(), any())).thenReturn(ourResourceVersionMap);
	}

	@Test
	public void testGetActiveUniqueSearchParams_Empty() {
		assertThat(mySearchParamRegistry.getActiveUniqueSearchParams("Patient"), is(empty()));
	}

	@Test
	public void testGetActiveSearchParamsRetries() {
		AtomicBoolean retried = new AtomicBoolean(false);
		when(myResourceVersionSvc.getVersionMap(anyString(), any())).thenAnswer(t -> {
			if (myAnswerCount == 0) {
				myAnswerCount++;
				retried.set(true);
				throw new InternalErrorException("this is an error!");
			}

			return ourResourceVersionMap;
		});

		assertFalse(retried.get());
		mySearchParamRegistry.forceRefresh();
		Map<String, RuntimeSearchParam> activeSearchParams = mySearchParamRegistry.getActiveSearchParams("Patient");
		assertTrue(retried.get());
		assertEquals(ourBuiltInSearchParams.getSearchParamMap("Patient").size(), activeSearchParams.size());
	}

	@Test
	public void testAddActiveSearchparam() {
		// Initialize the registry
		mySearchParamRegistry.forceRefresh();

		resetDatabaseToOrigSearchParamsPlusNewOneWithStatus(Enumerations.PublicationStatus.ACTIVE);

		mySearchParamRegistry.forceRefresh();
		Map<String, RuntimeSearchParam> outcome = mySearchParamRegistry.getActiveSearchParams("Patient");

		RuntimeSearchParam converted = outcome.get("foo");
		assertNotNull(converted);

		assertEquals(1, converted.getExtensions("http://foo").size());
		IPrimitiveType<?> value = (IPrimitiveType<?>) converted.getExtensions("http://foo").get(0).getValue();
		assertEquals("FOO", value.getValueAsString());
	}

	private List<ResourceTable> resetDatabaseToOrigSearchParamsPlusNewOneWithStatus(Enumerations.PublicationStatus theStatus) {
		// Add a new search parameter entity
		List<ResourceTable> newEntities = new ArrayList(ourEntities);
		newEntities.add(createEntity(++ourLastId, 1));
		resetMock(theStatus, newEntities);
		return newEntities;
	}

	private void resetMock(Enumerations.PublicationStatus theStatus, List<ResourceTable> theNewEntities) {
		ResourceVersionMap resourceVersionMap = ResourceVersionMap.fromResourceTableEntities(theNewEntities);
		when(myResourceVersionSvc.getVersionMap(anyString(), any())).thenReturn(resourceVersionMap);

		// When we ask for the new entity, return our foo search parameter
		when(mySearchParamProvider.search(any())).thenReturn(new SimpleBundleProvider(buildSearchParameter(theStatus)));
	}

	@Nonnull
	private SearchParameter buildSearchParameter(Enumerations.PublicationStatus theStatus) {
		SearchParameter searchParameter = new SearchParameter();
		searchParameter.setCode("foo");
		searchParameter.setStatus(theStatus);
		searchParameter.setType(Enumerations.SearchParamType.TOKEN);
		searchParameter.setExpression("Patient.name");
		searchParameter.addBase("Patient");
		searchParameter.addExtension("http://foo", new StringType("FOO"));
		searchParameter.addExtension("http://bar", new StringType("BAR"));

		// Invalid entries
		searchParameter.addExtension("http://bar", null);
		searchParameter.addExtension(null, new StringType("BAR"));
		return searchParameter;
	}

}
