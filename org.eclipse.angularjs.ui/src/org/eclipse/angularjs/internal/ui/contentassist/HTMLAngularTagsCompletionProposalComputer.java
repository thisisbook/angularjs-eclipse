/*******************************************************************************
 * Copyright (c) 2013 Angelo ZERR.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:      
 *     Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *******************************************************************************/
package org.eclipse.angularjs.internal.ui.contentassist;

import java.util.List;

import org.eclipse.angularjs.core.AngularProject;
import org.eclipse.angularjs.core.DOMSSEDirectiveProvider;
import org.eclipse.angularjs.core.utils.DOMUtils;
import org.eclipse.angularjs.internal.core.documentModel.parser.AngularRegionContext;
import org.eclipse.angularjs.internal.ui.ImageResource;
import org.eclipse.angularjs.internal.ui.Trace;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.swt.graphics.Image;
import org.eclipse.wst.sse.core.internal.provisional.text.IStructuredDocumentRegion;
import org.eclipse.wst.sse.core.internal.provisional.text.ITextRegion;
import org.eclipse.wst.sse.ui.contentassist.CompletionProposalInvocationContext;
import org.eclipse.wst.sse.ui.internal.contentassist.ContentAssistUtils;
import org.eclipse.wst.sse.ui.internal.contentassist.CustomCompletionProposal;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMAttr;
import org.eclipse.wst.xml.core.internal.provisional.document.IDOMNode;
import org.eclipse.wst.xml.ui.internal.contentassist.ContentAssistRequest;
import org.eclipse.wst.xml.ui.internal.contentassist.DefaultXMLCompletionProposalComputer;
import org.eclipse.wst.xml.ui.internal.contentassist.XMLRelevanceConstants;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import tern.angular.AngularType;
import tern.angular.modules.AngularModulesManager;
import tern.angular.modules.Directive;
import tern.angular.modules.IDirectiveCollector;
import tern.angular.protocol.HTMLTernAngularHelper;
import tern.angular.protocol.TernAngularQuery;
import tern.angular.protocol.completions.TernAngularCompletionsQuery;
import tern.eclipse.ide.core.IDETernProject;
import tern.server.ITernServer;
import tern.server.protocol.TernDoc;
import tern.server.protocol.completions.ITernCompletionCollector;

/**
 * Completion in HTML editor for :
 * 
 * <ul>
 * <li>attribute name with angular directive (ex : ng-app).</li>
 * <li>attribute value with angular module, controller, model.</li>
 * </ul>
 * 
 */
public class HTMLAngularTagsCompletionProposalComputer extends
		DefaultXMLCompletionProposalComputer {

	@Override
	protected void addAttributeNameProposals(
			final ContentAssistRequest contentAssistRequest,
			CompletionProposalInvocationContext context) {
		// Check if project has angular nature
		IDOMNode element = (IDOMNode) contentAssistRequest.getNode();
		if (DOMUtils.hasAngularNature(element)) {

			// completion for attribute name with angular directive (ex :
			// ng-app)
			String tagName = element.getNodeName();
			String directiveName = contentAssistRequest.getMatchString();
			IDOMAttr attr = DOMUtils.getAttrByRegion(element,
					contentAssistRequest.getRegion());
			// get angular attribute name of the element
			final List<String> existingDirectiveNames = DOMUtils
					.getAngularDirectiveNames(
							element instanceof Element ? (Element) element
									: null, attr);

			// Starts directives completion.
			AngularModulesManager.getInstance().collectDirectives(tagName,
					directiveName, false, new IDirectiveCollector() {

						@Override
						public void add(Directive directive, String name) {

							if (existingDirectiveNames.contains(directive
									.getName())) {
								// The directive already exists in the element,
								// completion should not show it.
								return;
							}

							// Add the directive in the completion.
							String replacementString = name + "=\"\"";
							int replacementOffset = contentAssistRequest
									.getReplacementBeginPosition();
							int replacementLength = contentAssistRequest
									.getReplacementLength();
							int cursorPosition = getCursorPositionForProposedText(replacementString);

							Image image = ImageResource
									.getImage(ImageResource.IMG_DIRECTIVE);
							String displayString = name + " - "
									+ directive.getModule().getName();
							IContextInformation contextInformation = null;
							String additionalProposalInfo = directive
									.getHTMLDescription();
							int relevance = XMLRelevanceConstants.R_XML_ATTRIBUTE_NAME;

							ICompletionProposal proposal = new CustomCompletionProposal(
									replacementString, replacementOffset,
									replacementLength, cursorPosition, image,
									displayString, contextInformation,
									additionalProposalInfo, relevance);
							contentAssistRequest.addProposal(proposal);

						}
					});
		}
		super.addAttributeNameProposals(contentAssistRequest, context);
	}

	@Override
	protected void addAttributeValueProposals(
			final ContentAssistRequest contentAssistRequest,
			CompletionProposalInvocationContext context) {
		// Check if project has angular nature
		IDOMNode element = (IDOMNode) contentAssistRequest.getNode();
		if (DOMUtils.hasAngularNature(element)) {
			// is angular directive attribute?
			Directive directive = DOMUtils.getAngularDirective(element,
					contentAssistRequest.getRegion());
			AngularType angularType = directive != null ? directive.getType()
					: null;
			if (angularType != null) {
				if (angularType.equals(AngularType.unknown)
						|| angularType.equals(AngularType.directiveRepeat))
					angularType = AngularType.model;
				int startIndex = contentAssistRequest.getMatchString()
						.startsWith("\"") ? 1 : 0;
				populateAngularProposals(contentAssistRequest, element,
						angularType, startIndex);
			} else {
				// is angular expression inside attribute?
				String matchingString = contentAssistRequest.getMatchString();
				int index = matchingString.lastIndexOf("{{");
				if (index != -1) {
					populateAngularProposals(contentAssistRequest, element,
							AngularType.model, index);
				}
			}
		}
		super.addAttributeValueProposals(contentAssistRequest, context);
	}

	private void populateAngularProposals(
			final ContentAssistRequest contentAssistRequest, IDOMNode element,
			final AngularType angularType, final Integer startIndex) {
		IFile file = DOMUtils.getFile(element);
		IProject eclipseProject = file.getProject();
		try {
			IDETernProject ternProject = AngularProject
					.getTernProject(eclipseProject);

			// get the expression to use for Tern completion
			String expression = getExpression(contentAssistRequest, startIndex);

			final int replacementOffset = getReplacementOffset(
					contentAssistRequest, angularType,
					element.getNodeType() != Node.TEXT_NODE);

			// Create Tern doc + query
			TernAngularQuery query = new TernAngularCompletionsQuery(
					angularType);
			query.setExpression(expression);
			TernDoc doc = HTMLTernAngularHelper.createDoc(element,
					DOMSSEDirectiveProvider.getInstance(), file,
					ternProject.getFileManager(), query);

			// Execute Tern completion
			final ITernServer ternServer = ternProject.getTernServer();
			ITernCompletionCollector collector = new ITernCompletionCollector() {

				@Override
				public void addProposal(String name, String type,
						String origin, Object doc, int pos, Object completion) {

					AngularCompletionProposal proposal = new AngularCompletionProposal(
							name, type, origin, doc, pos, completion,
							ternServer, angularType, replacementOffset);
					if (isModuleOrController(angularType)) {
						// in the case of "module", "controller" completion
						// the value must replace the existing value.
						String replacementString = "\"" + name + "\"";
						int replacementLength = contentAssistRequest
								.getReplacementLength();
						int cursorPosition = getCursorPositionForProposedText(replacementString);
						proposal.setReplacementString(replacementString);
						proposal.setReplacementLength(replacementLength);
						proposal.setCursorPosition(cursorPosition - 2);
						proposal.setReplacementOffset(replacementOffset);
						proposal.setImage(getImage(angularType));
					}
					contentAssistRequest.addProposal(proposal);

				}
			};
			ternServer.request(doc, collector);

		} catch (Exception e) {
			Trace.trace(Trace.SEVERE, "Error while tern completion.", e);
		}
	}

	/**
	 * Returns the expression to use for tern completion.
	 * 
	 * @param contentAssistRequest
	 * @param startIndex
	 * @return
	 */
	private String getExpression(ContentAssistRequest contentAssistRequest,
			Integer startIndex) {
		String expression = contentAssistRequest.getMatchString();
		if (startIndex != null) {
			// start index is not null , this case comes from when completion is
			// done in attribute :
			// 1) when completion is done inside an attribute <span
			// ng-app="MyModu
			// in this case the expression to use is 'MyModu' and not
			// '"MyModu'
			// 2) when completion is done inside an
			// attribute which define {{
			// ex : <span class="done-{{to
			// in this case, the expression to use is 'to' and not
			// '"done-{{to'
			expression = expression.substring(startIndex, expression.length());
		}
		return expression;
	}

	/**
	 * Returns the replacement offset.
	 * 
	 * @param contentAssistRequest
	 * @param angularType
	 * @param isAttr
	 * @return
	 */
	private int getReplacementOffset(ContentAssistRequest contentAssistRequest,
			AngularType angularType, boolean isAttr) {
		int replacementOffset = contentAssistRequest
				.getReplacementBeginPosition();
		if (isAttr) {
			// the completion is done in an attribute.
			if (!isModuleOrController(angularType)) {
				// getReplacementBeginPosition returns the position of the
				// starts of the attribute value (or quote).
				// in the case of attribute different from "module",
				// "controller", the replacement offset must
				// be the position where completion starts (ex : ng-model="todo.
				// => the position should be after todo. and before.
				replacementOffset += contentAssistRequest.getMatchString()
						.length();
			}
		}
		return replacementOffset;
	}

	@Override
	protected ContentAssistRequest computeCompletionProposals(
			String matchString, ITextRegion completionRegion,
			IDOMNode treeNode, IDOMNode xmlnode,
			CompletionProposalInvocationContext context) {
		String regionType = completionRegion.getType();
		if (regionType == AngularRegionContext.ANGULAR_EXPRESSION_OPEN
				|| regionType == AngularRegionContext.ANGULAR_EXPRESSION_CONTENT) {

			// completion for Angular expression {{}} insitde text node.
			int documentPosition = context.getInvocationOffset();
			IStructuredDocumentRegion documentRegion = ContentAssistUtils
					.getStructuredDocumentRegion(context.getViewer(),
							documentPosition);

			int length = documentPosition - documentRegion.getStartOffset();
			if (length > 1) {
				// here we have {{
				String match = documentRegion.getText().substring(2, length);

				ContentAssistRequest contentAssistRequest = new ContentAssistRequest(
						treeNode, treeNode.getParentNode(), documentRegion,
						completionRegion, documentPosition, 0, match);

				populateAngularProposals(contentAssistRequest, treeNode,
						AngularType.model, null);

				return contentAssistRequest;
			}
		}
		return super.computeCompletionProposals(matchString, completionRegion,
				treeNode, xmlnode, context);
	}

	/**
	 * Returns true if the given angular type is module or controller and false
	 * otherwise.
	 * 
	 * @param angularType
	 * @return
	 */
	private boolean isModuleOrController(final AngularType angularType) {
		return angularType == AngularType.module
				|| angularType == AngularType.controller;
	}

	/**
	 * Returns the image to use for completion according to teh given angular
	 * type.
	 * 
	 * @param angularType
	 * @return
	 */
	private static Image getImage(AngularType angularType) {
		switch (angularType) {
		case module:
			return ImageResource.getImage(ImageResource.IMG_ANGULARJS);
		case controller:
			return ImageResource.getImage(ImageResource.IMG_CONTROLLER);
		default:
			return null;
		}
	}

}
