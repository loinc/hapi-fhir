package ca.uhn.fhir.rest.method;

/*
 * #%L
 * HAPI FHIR Library
 * %%
 * Copyright (C) 2014 University Health Network
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.utils.DateUtils;

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import ca.uhn.fhir.model.api.Bundle;
import ca.uhn.fhir.model.api.BundleEntry;
import ca.uhn.fhir.model.api.IResource;
import ca.uhn.fhir.model.api.ResourceMetadataKeyEnum;
import ca.uhn.fhir.model.api.Tag;
import ca.uhn.fhir.model.api.TagList;
import ca.uhn.fhir.model.api.annotation.ResourceDef;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationSystemEnum;
import ca.uhn.fhir.model.dstu.valueset.RestfulOperationTypeEnum;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.model.primitive.InstantDt;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.rest.client.exceptions.InvalidResponseException;
import ca.uhn.fhir.rest.param.IParameter;
import ca.uhn.fhir.rest.server.Constants;
import ca.uhn.fhir.rest.server.EncodingEnum;
import ca.uhn.fhir.rest.server.RestfulServer;
import ca.uhn.fhir.rest.server.RestfulServer.NarrativeModeEnum;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;

abstract class BaseResourceReturningMethodBinding extends BaseMethodBinding<Object> {
	private static final org.slf4j.Logger ourLog = org.slf4j.LoggerFactory.getLogger(BaseResourceReturningMethodBinding.class);
	protected static final Set<String> ALLOWED_PARAMS;
	static {
		HashSet<String> set = new HashSet<String>();
		set.add(Constants.PARAM_FORMAT);
		set.add(Constants.PARAM_NARRATIVE);
		set.add(Constants.PARAM_PRETTY);
		ALLOWED_PARAMS = Collections.unmodifiableSet(set);
	}

	private MethodReturnTypeEnum myMethodReturnType;
	private String myResourceName;
	private Class<? extends IResource> myResourceType;

	public BaseResourceReturningMethodBinding(Class<? extends IResource> theReturnResourceType, Method theMethod, FhirContext theConetxt, Object theProvider) {
		super(theMethod, theConetxt, theProvider);

		Class<?> methodReturnType = theMethod.getReturnType();
		if (Collection.class.isAssignableFrom(methodReturnType)) {
			myMethodReturnType = MethodReturnTypeEnum.LIST_OF_RESOURCES;
		} else if (IResource.class.isAssignableFrom(methodReturnType)) {
			myMethodReturnType = MethodReturnTypeEnum.RESOURCE;
		} else if (Bundle.class.isAssignableFrom(methodReturnType)) {
			myMethodReturnType = MethodReturnTypeEnum.BUNDLE;
		} else {
			throw new ConfigurationException("Invalid return type '" + methodReturnType.getCanonicalName() + "' on method '" + theMethod.getName() + "' on type: " + theMethod.getDeclaringClass().getCanonicalName());
		}

		myResourceType = theReturnResourceType;
		if (theReturnResourceType != null) {
			ResourceDef resourceDefAnnotation = theReturnResourceType.getAnnotation(ResourceDef.class);
			if (resourceDefAnnotation == null) {
				throw new ConfigurationException(theReturnResourceType.getCanonicalName() + " has no @" + ResourceDef.class.getSimpleName() + " annotation");
			}
			myResourceName = resourceDefAnnotation.name();
		}
	}

	public MethodReturnTypeEnum getMethodReturnType() {
		return myMethodReturnType;
	}

	@Override
	public String getResourceName() {
		return myResourceName;
	}

	public abstract ReturnTypeEnum getReturnType();

	@Override
	public Object invokeClient(String theResponseMimeType, Reader theResponseReader, int theResponseStatusCode, Map<String, List<String>> theHeaders) throws IOException {
		IParser parser = createAppropriateParserForParsingResponse(theResponseMimeType, theResponseReader, theResponseStatusCode);

		switch (getReturnType()) {
		case BUNDLE: {
			Bundle bundle;
			if (myResourceType != null) {
				bundle = parser.parseBundle(myResourceType, theResponseReader);
			} else {
				bundle = parser.parseBundle(theResponseReader);
			}
			switch (getMethodReturnType()) {
			case BUNDLE:
				return bundle;
			case LIST_OF_RESOURCES:
				return bundle.toListOfResources();
			case RESOURCE:
				List<IResource> list = bundle.toListOfResources();
				if (list.size() == 0) {
					return null;
				} else if (list.size() == 1) {
					return list.get(0);
				} else {
					throw new InvalidResponseException(theResponseStatusCode, "FHIR server call returned a bundle with multiple resources, but this method is only able to returns one.");
				}
			}
			break;
		}
		case RESOURCE: {
			IResource resource;
			if (myResourceType != null) {
				resource = parser.parseResource(myResourceType, theResponseReader);
			} else {
				resource = parser.parseResource(theResponseReader);
			}

			List<String> lmHeaders = theHeaders.get(Constants.HEADER_LAST_MODIFIED_LOWERCASE);
			if (lmHeaders != null && lmHeaders.size() > 0 && StringUtils.isNotBlank(lmHeaders.get(0))) {
				String headerValue = lmHeaders.get(0);
				Date headerDateValue;
				try {
					headerDateValue = DateUtils.parseDate(headerValue);
					InstantDt lmValue = new InstantDt(headerDateValue);
					resource.getResourceMetadata().put(ResourceMetadataKeyEnum.UPDATED, lmValue);
				} catch (Exception e) {
					ourLog.warn("Unable to parse date string '{}'. Error is: {}", headerValue, e.toString());
				}
			}

			switch (getMethodReturnType()) {
			case BUNDLE:
				return Bundle.withSingleResource(resource);
			case LIST_OF_RESOURCES:
				return Collections.singletonList(resource);
			case RESOURCE:
				return resource;
			}
			break;
		}
		}

		throw new IllegalStateException("Should not get here!");
	}

	public abstract List<IResource> invokeServer(Request theRequest, Object[] theMethodParams) throws InvalidRequestException, InternalErrorException;

	@Override
	public void invokeServer(RestfulServer theServer, Request theRequest, HttpServletResponse theResponse) throws BaseServerResponseException, IOException {

		// Pretty print
		boolean prettyPrint = RestfulServer.prettyPrintResponse(theRequest);

		// Narrative mode
		Map<String, String[]> requestParams = theRequest.getParameters();
		String[] narrative = requestParams.remove(Constants.PARAM_NARRATIVE);
		NarrativeModeEnum narrativeMode = null;
		if (narrative != null && narrative.length > 0) {
			narrativeMode = NarrativeModeEnum.valueOfCaseInsensitive(narrative[0]);
		}
		if (narrativeMode == null) {
			narrativeMode = NarrativeModeEnum.NORMAL;
		}

		// Determine response encoding
		EncodingEnum responseEncoding = RestfulServer.determineResponseEncoding(theRequest);

		// Is this request coming from a browser
		String uaHeader = theRequest.getServletRequest().getHeader("user-agent");
		boolean requestIsBrowser = false;
		if (uaHeader != null && uaHeader.contains("Mozilla")) {
			requestIsBrowser = true;
		}

		Object requestObject = parseRequestObject(theRequest);

		// Method params
		Object[] params = new Object[getParameters().size()];
		for (int i = 0; i < getParameters().size(); i++) {
			IParameter param = getParameters().get(i);
			if (param != null) {
				params[i] = param.translateQueryParametersIntoServerArgument(theRequest, requestObject);
			}
		}

		List<IResource> result = invokeServer(theRequest, params);
		switch (getReturnType()) {
		case BUNDLE:
			streamResponseAsBundle(theServer, theResponse, result, responseEncoding, theRequest.getFhirServerBase(), theRequest.getCompleteUrl(), prettyPrint, requestIsBrowser, narrativeMode);
			break;
		case RESOURCE:
			if (result.size() == 0) {
				throw new ResourceNotFoundException(theRequest.getId());
			} else if (result.size() > 1) {
				throw new InternalErrorException("Method returned multiple resources");
			}
			streamResponseAsResource(theServer, theResponse, result.get(0), responseEncoding, prettyPrint, requestIsBrowser, narrativeMode);
			break;
		}
	}

	private IParser getNewParser(EncodingEnum theResponseEncoding, boolean thePrettyPrint, NarrativeModeEnum theNarrativeMode) {
		IParser parser;
		switch (theResponseEncoding) {
		case JSON:
			parser = getContext().newJsonParser();
			break;
		case XML:
		default:
			parser = getContext().newXmlParser();
			break;
		}
		return parser.setPrettyPrint(thePrettyPrint).setSuppressNarratives(theNarrativeMode == NarrativeModeEnum.SUPPRESS);
	}

	private void streamResponseAsBundle(RestfulServer theServer, HttpServletResponse theHttpResponse, List<IResource> theResult, EncodingEnum theResponseEncoding, String theServerBase, String theCompleteUrl, boolean thePrettyPrint, boolean theRequestIsBrowser,
			NarrativeModeEnum theNarrativeMode) throws IOException {
		assert !theServerBase.endsWith("/");

		theHttpResponse.setStatus(200);

		if (theRequestIsBrowser && theServer.isUseBrowserFriendlyContentTypes()) {
			theHttpResponse.setContentType(theResponseEncoding.getBrowserFriendlyBundleContentType());
		} else if (theNarrativeMode == NarrativeModeEnum.ONLY) {
			theHttpResponse.setContentType(Constants.CT_HTML);
		} else {
			theHttpResponse.setContentType(theResponseEncoding.getBundleContentType());
		}

		theHttpResponse.setCharacterEncoding(Constants.CHARSET_UTF_8);

		theServer.addHeadersToResponse(theHttpResponse);

		Bundle bundle = createBundleFromResourceList(getContext(), getClass().getCanonicalName(), theResult, theResponseEncoding, theServerBase, theCompleteUrl, thePrettyPrint, theNarrativeMode);

		PrintWriter writer = theHttpResponse.getWriter();
		try {
			if (theNarrativeMode == NarrativeModeEnum.ONLY) {
				for (IResource next : theResult) {
					writer.append(next.getText().getDiv().getValueAsString());
					writer.append("<hr/>");
				}
			} else {
				getNewParser(theResponseEncoding, thePrettyPrint, theNarrativeMode).encodeBundleToWriter(bundle, writer);
			}
		} finally {
			writer.close();
		}
	}

	private void streamResponseAsResource(RestfulServer theServer, HttpServletResponse theHttpResponse, IResource theResource, EncodingEnum theResponseEncoding, boolean thePrettyPrint, boolean theRequestIsBrowser, NarrativeModeEnum theNarrativeMode) throws IOException {

		theHttpResponse.setStatus(200);
		if (theRequestIsBrowser && theServer.isUseBrowserFriendlyContentTypes()) {
			theHttpResponse.setContentType(theResponseEncoding.getBrowserFriendlyBundleContentType());
		} else if (theNarrativeMode == NarrativeModeEnum.ONLY) {
			theHttpResponse.setContentType(Constants.CT_HTML);
		} else {
			theHttpResponse.setContentType(theResponseEncoding.getResourceContentType());
		}
		theHttpResponse.setCharacterEncoding(Constants.CHARSET_UTF_8);

		theServer.addHeadersToResponse(theHttpResponse);

		InstantDt lastUpdated = getInstantFromMetadataOrNullIfNone(theResource.getResourceMetadata(), ResourceMetadataKeyEnum.UPDATED);
		if (lastUpdated != null) {
			theHttpResponse.addHeader(Constants.HEADER_LAST_MODIFIED, lastUpdated.getValueAsString());
		}

		TagList list = (TagList) theResource.getResourceMetadata().get(ResourceMetadataKeyEnum.TAG_LIST);
		if (list != null) {
			for (Tag tag : list) {
				if (StringUtils.isNotBlank(tag.getTerm())) {
					theHttpResponse.addHeader(Constants.HEADER_CATEGORY, tag.toHeaderValue());
				}
			}
		}

		PrintWriter writer = theHttpResponse.getWriter();
		try {
			if (theNarrativeMode == NarrativeModeEnum.ONLY) {
				writer.append(theResource.getText().getDiv().getValueAsString());
			} else {
				getNewParser(theResponseEncoding, thePrettyPrint, theNarrativeMode).encodeResourceToWriter(theResource, writer);
			}
		} finally {
			writer.close();
		}

	}

	/**
	 * Subclasses may override
	 */
	protected Object parseRequestObject(@SuppressWarnings("unused") Request theRequest) {
		return null;
	}

	public static Bundle createBundleFromResourceList(FhirContext theContext, String theAuthor, List<IResource> theResult, EncodingEnum theResponseEncoding, String theServerBase, String theCompleteUrl, boolean thePrettyPrint, NarrativeModeEnum theNarrativeMode) {
		Bundle bundle = new Bundle();
		bundle.getAuthorName().setValue(theAuthor);
		bundle.getBundleId().setValue(UUID.randomUUID().toString());
		bundle.getPublished().setToCurrentTimeInLocalTimeZone();
		bundle.getLinkBase().setValue(theServerBase);
		bundle.getLinkSelf().setValue(theCompleteUrl);

		for (IResource next : theResult) {
			BundleEntry entry = new BundleEntry();
			bundle.getEntries().add(entry);

			entry.setResource(next);
			TagList list = (TagList) next.getResourceMetadata().get(ResourceMetadataKeyEnum.TAG_LIST);
			if (list != null) {
				for (Tag tag : list) {
					if (StringUtils.isNotBlank(tag.getTerm())) {
						entry.addCategory().setTerm(tag.getTerm()).setLabel(tag.getLabel()).setScheme(tag.getScheme());
					}
				}
			}

			RuntimeResourceDefinition def = theContext.getResourceDefinition(next);

			if (next.getId() != null && StringUtils.isNotBlank(next.getId().getValue())) {
				entry.getTitle().setValue(def.getName() + " " + next.getId().getValue());

				StringBuilder b = new StringBuilder();
				b.append(theServerBase);
				if (b.length() > 0 && b.charAt(b.length() - 1) != '/') {
					b.append('/');
				}
				b.append(def.getName());
				b.append('/');
				String resId = next.getId().getUnqualifiedId();
				b.append(resId);

				entry.getId().setValue(b.toString());

				if (isNotBlank(next.getId().getUnqualifiedVersionId())) {
					b.append('/');
					b.append(Constants.PARAM_HISTORY);
					b.append('/');
					b.append(next.getId().getUnqualifiedVersionId());
				} else {
					IdDt versionId = getIdFromMetadataOrNullIfNone(next.getResourceMetadata(), ResourceMetadataKeyEnum.VERSION_ID);
					if (versionId != null) {
						b.append('/');
						b.append(Constants.PARAM_HISTORY);
						b.append('/');
						b.append(versionId.getValue());
					}
				}

				InstantDt published = getInstantFromMetadataOrNullIfNone(next.getResourceMetadata(), ResourceMetadataKeyEnum.PUBLISHED);
				if (published == null) {
					entry.getPublished().setToCurrentTimeInLocalTimeZone();
				} else {
					entry.setPublished(published);
				}

				InstantDt updated = getInstantFromMetadataOrNullIfNone(next.getResourceMetadata(), ResourceMetadataKeyEnum.UPDATED);
				if (updated != null) {
					entry.setUpdated(updated);
				}

				InstantDt deleted = getInstantFromMetadataOrNullIfNone(next.getResourceMetadata(), ResourceMetadataKeyEnum.DELETED_AT);
				if (deleted != null) {
					entry.setDeleted(deleted);
				}

				IdDt previous = getIdFromMetadataOrNullIfNone(next.getResourceMetadata(), ResourceMetadataKeyEnum.PREVIOUS_ID);
				if (previous != null) {
					entry.getLinkAlternate().setValue(previous.toQualifiedUrl(theServerBase, def.getName()));
				}

				TagList tagList = getTagListFromMetadataOrNullIfNone(next.getResourceMetadata(), ResourceMetadataKeyEnum.TAG_LIST);
				if (tagList != null) {
					for (Tag nextTag : tagList) {
						entry.addCategory(nextTag);
					}
				}

//				boolean haveQ = false;
//				if (thePrettyPrint) {
//					b.append('?').append(Constants.PARAM_PRETTY).append("=true");
//					haveQ = true;
//				}
//				if (theResponseEncoding == EncodingEnum.JSON) {
//					if (!haveQ) {
//						b.append('?');
//						haveQ = true;
//					} else {
//						b.append('&');
//					}
//					b.append(Constants.PARAM_FORMAT).append("=json");
//				}
//				if (theNarrativeMode != NarrativeModeEnum.NORMAL) {
//					b.append(Constants.PARAM_NARRATIVE).append("=").append(theNarrativeMode.name().toLowerCase());
//				}
				entry.getLinkSelf().setValue(b.toString());
			}
		}

		bundle.getTotalResults().setValue(theResult.size());
		return bundle;
	}

	private static InstantDt getInstantFromMetadataOrNullIfNone(Map<ResourceMetadataKeyEnum, Object> theResourceMetadata, ResourceMetadataKeyEnum theKey) {
		Object retValObj = theResourceMetadata.get(theKey);
		if (retValObj == null) {
			return null;
		} else if (retValObj instanceof Date) {
			return new InstantDt((Date) retValObj);
		} else if (retValObj instanceof InstantDt) {
			if (((InstantDt) retValObj).isEmpty()) {
				return null;
			} else {
				return (InstantDt) retValObj;
			}
		}
		throw new InternalErrorException("Found an object of type '" + retValObj.getClass().getCanonicalName() + "' in resource metadata for key " + theKey.name() + " - Expected " + InstantDt.class.getCanonicalName());
	}

	private static TagList getTagListFromMetadataOrNullIfNone(Map<ResourceMetadataKeyEnum, Object> theResourceMetadata, ResourceMetadataKeyEnum theKey) {
		Object retValObj = theResourceMetadata.get(theKey);
		if (retValObj == null) {
			return null;
		} else if (retValObj instanceof TagList) {
			if (((TagList) retValObj).isEmpty()) {
				return null;
			} else {
				return (TagList) retValObj;
			}
		}
		throw new InternalErrorException("Found an object of type '" + retValObj.getClass().getCanonicalName() + "' in resource metadata for key " + theKey.name() + " - Expected " + TagList.class.getCanonicalName());
	}

	protected static IdDt getIdFromMetadataOrNullIfNone(Map<ResourceMetadataKeyEnum, Object> theResourceMetadata, ResourceMetadataKeyEnum theKey) {
		Object retValObj = theResourceMetadata.get(theKey);
		if (retValObj == null) {
			return null;
		} else if (retValObj instanceof String) {
			if (isNotBlank((String) retValObj)) {
				return new IdDt((String) retValObj);
			} else {
				return null;
			}
		} else if (retValObj instanceof IdDt) {
			if (((IdDt) retValObj).isEmpty()) {
				return null;
			} else {
				return (IdDt) retValObj;
			}
		}
		throw new InternalErrorException("Found an object of type '" + retValObj.getClass().getCanonicalName() + "' in resource metadata for key " + theKey.name() + " - Expected " + IdDt.class.getCanonicalName());
	}

	public enum MethodReturnTypeEnum {
		BUNDLE, LIST_OF_RESOURCES, RESOURCE
	}

	public enum ReturnTypeEnum {
		BUNDLE, RESOURCE
	}

}
