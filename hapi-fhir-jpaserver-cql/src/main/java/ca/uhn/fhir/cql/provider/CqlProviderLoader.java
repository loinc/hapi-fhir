package ca.uhn.fhir.cql.provider;

/*-
 * #%L
 * HAPI FHIR - Enterprise Master Patient Index
 * %%
 * Copyright (C) 2014 - 2020 University Health Network
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

import ca.uhn.fhir.context.ConfigurationException;
import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.api.dao.DaoRegistry;
import ca.uhn.fhir.rest.server.provider.ResourceProviderFactory;
import com.google.common.annotations.VisibleForTesting;
import org.opencds.cqf.common.evaluation.EvaluationProviderFactory;
import org.opencds.cqf.common.providers.LibraryResolutionProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CqlProviderLoader {
	@Autowired
	private FhirContext myFhirContext;
	@Autowired
	private ResourceProviderFactory myResourceProviderFactory;
	@Autowired
	DaoRegistry myDaoRegistry;

	public void loadProvider() {
		EvaluationProviderFactory evaluationProviderFactory = null;

		switch (myFhirContext.getVersion().getVersion()) {
			case DSTU3:
				myResourceProviderFactory.addSupplier(() -> buildDstu3Provider(evaluationProviderFactory));
				break;
			case R4:
				myResourceProviderFactory.addSupplier(() -> buildR4Provider(evaluationProviderFactory));
				break;
			default:
				throw new ConfigurationException("CQL not supported for FHIR version " + myFhirContext.getVersion().getVersion());
		}
	}

	@VisibleForTesting
	org.opencds.cqf.dstu3.providers.MeasureOperationsProvider buildDstu3Provider(EvaluationProviderFactory theEvaluationProviderFactory) {
		org.opencds.cqf.tooling.library.stu3.NarrativeProvider narrativeProviderDstu3 = null;
		org.opencds.cqf.dstu3.providers.HQMFProvider hqmfProviderDstu3 = null;
		ca.uhn.fhir.jpa.rp.dstu3.MeasureResourceProvider measureResourceProviderDstu3 = null;
		LibraryResolutionProvider<org.hl7.fhir.dstu3.model.Library> libraryResolutionProviderDstu3 = null;
		return new org.opencds.cqf.dstu3.providers.MeasureOperationsProvider(myDaoRegistry, theEvaluationProviderFactory, narrativeProviderDstu3, hqmfProviderDstu3, libraryResolutionProviderDstu3, measureResourceProviderDstu3);
	}

	@VisibleForTesting
	org.opencds.cqf.r4.providers.MeasureOperationsProvider buildR4Provider(EvaluationProviderFactory theEvaluationProviderFactory) {
		org.opencds.cqf.tooling.library.r4.NarrativeProvider narrativeProviderR4 = null;
		org.opencds.cqf.r4.providers.HQMFProvider hqmfProviderR4 = null;
		ca.uhn.fhir.jpa.rp.r4.MeasureResourceProvider measureResourceProviderR4 = null;
		LibraryResolutionProvider<org.hl7.fhir.r4.model.Library> libraryResolutionProviderR4 = null;
		return new org.opencds.cqf.r4.providers.MeasureOperationsProvider(myDaoRegistry, theEvaluationProviderFactory, narrativeProviderR4, hqmfProviderR4, libraryResolutionProviderR4, measureResourceProviderR4);
	}
}

