import {copyNode, getNodeByPath, setNodeProperty} from '@jahia/cypress';
import {CONTENT_PATH} from '../../support/constants';
import {
	buildSelectOptionsPropertyValues,
	createConditionalLogicForm,
	createConditionalLogicNoMatchingSourceForm,
	createDuplicateSourceNameConditionalLogicForm,
	findConditionalLogicNode,
	getConditionalLogicNode,
	parseStoredLogicRule
} from '../../support/fixtures';
import {assertContentIntegrityClean} from '../../support/contentIntegrity';
import {useFormidableSite} from '../support/useFormidableSite';

interface NodeByPathResponse {
	data?: {
		jcr?: {
			nodeByPath?: {
				uuid?: string;
				properties?: Array<{
					name: string;
					values?: string[] | null;
				}> | null;
			} | null;
		} | null;
	};
}

const getNodeUuidByPath = (path: string): Cypress.Chainable<string> => getNodeByPath(path).then((response: NodeByPathResponse) => {
	const uuid = response.data?.jcr?.nodeByPath?.uuid;
	if (!uuid) {
		throw new Error(`Unable to resolve UUID for '${path}'`);
	}

	return uuid;
});

const storeLogicRule = (
	targetPath: string,
	sourcePath: string,
	sourceFieldName: string,
	sourceFieldType: string,
	operator: string,
	values: string[],
	logicId: string
) => getNodeUuidByPath(sourcePath).then(sourceNodeId => {
	const storedRule = JSON.stringify({
		logicId,
		sourceNodeId,
		sourceFieldName,
		sourceFieldType,
		operator,
		values
	});

	return setNodeProperty(targetPath, 'logics', [storedRule], 'en');
});

const waitForReferencedSourcePath = (targetPath: string, expectedSourcePath: string) => {
	cy.waitUntil(() => findConditionalLogicNode(targetPath).then(node => {
		const logicChild = node?.descendant?.children?.nodes?.[0];
		return logicChild?.property?.refNode?.path === expectedSourcePath;
	}));
};

describe('Form logic - 51 Conditional logic copy and paste', () => {
	useFormidableSite();

	it('copies a whole form and rebinds the weakref inside the copied form', () => {
		createConditionalLogicForm(`${Date.now()}-full-copy`).then(({formName, formPath, rolePath, targetPath}) => {
			storeLogicRule(targetPath, rolePath, 'role', 'fmdb:select', 'in', ['admin'], 'logic-role-admin');
			waitForReferencedSourcePath(targetPath, rolePath);

			const copiedFormName = `${formName}-copy`;
			const copiedFormPath = `${formPath}-copy`;
			const copiedRolePath = `${copiedFormPath}/fields/role`;
			const copiedTargetPath = `${copiedFormPath}/fields/nickname`;

			copyNode(formPath, CONTENT_PATH, copiedFormName);
			waitForReferencedSourcePath(copiedTargetPath, copiedRolePath);

			getConditionalLogicNode(copiedTargetPath).then(node => {
				const rawLogics = node.properties?.find(property => property.name === 'logics')?.values ?? [];
				expect(rawLogics).to.have.length(1);

				const storedRule = parseStoredLogicRule(rawLogics[0]);
				expect(storedRule.logicId).to.equal('logic-role-admin');
				expect(storedRule.sourceFieldName).to.equal('role');
				expect(storedRule.sourceNodeId).to.equal(node.descendant?.children?.nodes?.[0]?.property?.refNode?.uuid);

				const logicChild = node.descendant?.children?.nodes?.[0];
				expect(logicChild?.property?.refNode?.path).to.equal(copiedRolePath);
				expect(logicChild?.property?.refNode?.path).not.to.equal(rolePath);
			});

			assertContentIntegrityClean({startNode: copiedFormPath});
		});
	});

	it('copies a whole form with duplicate source names and keeps the correct source binding', () => {
		createDuplicateSourceNameConditionalLogicForm(`${Date.now()}-duplicates`).then(({
			formName,
			formPath,
			secondSourcePath,
			targetPath
		}) => {
			storeLogicRule(
				targetPath,
				secondSourcePath,
				'select-an-option',
				'fmdb:select',
				'in',
				['manager'],
				'logic-duplicate-source'
			);
			waitForReferencedSourcePath(targetPath, secondSourcePath);

			const copiedFormName = `${formName}-copy`;
			const copiedFormPath = `${formPath}-copy`;
			const copiedSecondSourcePath = `${copiedFormPath}/fields/reduction/select-an-option`;
			const copiedTargetPath = `${copiedFormPath}/fields/nickname`;

			copyNode(formPath, CONTENT_PATH, copiedFormName);
			waitForReferencedSourcePath(copiedTargetPath, copiedSecondSourcePath);

			setNodeProperty(
				copiedSecondSourcePath,
				'options',
				buildSelectOptionsPropertyValues([
					{value: 'manager', label: 'manager', selected: false},
					{value: 'staff', label: 'staff', selected: false},
					{value: 'super-admin', label: 'super-admin', selected: false}
				]),
				'en'
			);

			getConditionalLogicNode(copiedTargetPath).then(node => {
				const rawLogics = node.properties?.find(property => property.name === 'logics')?.values ?? [];
				expect(rawLogics).to.have.length(1);

				const storedRule = parseStoredLogicRule(rawLogics[0]);
				expect(storedRule.logicId).to.equal('logic-duplicate-source');
				expect(storedRule.sourceFieldName).to.equal('select-an-option');

				const logicChild = node.descendant?.children?.nodes?.[0];
				expect(logicChild?.property?.refNode?.path).to.equal(copiedSecondSourcePath);
				expect(logicChild?.property?.refNode?.path).not.to.equal(secondSourcePath);
			});

			getNodeByPath(copiedSecondSourcePath, ['options'], 'en').then((response: NodeByPathResponse) => {
				const optionValues = response.data?.jcr?.nodeByPath?.properties?.find(property => property.name === 'options')?.values ?? [];
				expect(optionValues.join(' ')).to.include('super-admin');
				expect(optionValues.join(' ')).not.to.include('"alpha"');
			});

			assertContentIntegrityClean({startNode: copiedFormPath});
		});
	});

	it('copies a single logic field without a matching local source and keeps only the JSON rule', () => {
		createConditionalLogicForm(`${Date.now()}-source-field`).then(({rolePath, targetPath: sourceTargetPath}) => {
			storeLogicRule(sourceTargetPath, rolePath, 'role', 'fmdb:select', 'in', ['admin'], 'logic-single-field-copy');
			waitForReferencedSourcePath(sourceTargetPath, rolePath);

			createConditionalLogicNoMatchingSourceForm(`${Date.now()}-target-form`).then(({formPath}) => {
				const copiedTargetPath = `${formPath}/fields/copied-nickname`;

				copyNode(sourceTargetPath, `${formPath}/fields`, 'copied-nickname');

				cy.waitUntil(() => findConditionalLogicNode(copiedTargetPath).then(node => {
					if (!node) {
						return false;
					}

					return (node.descendant?.children?.nodes ?? []).length === 0;
				}));

				getConditionalLogicNode(copiedTargetPath).then(node => {
					const rawLogics = node.properties?.find(property => property.name === 'logics')?.values ?? [];
					expect(rawLogics).to.have.length(1);

					const storedRule = parseStoredLogicRule(rawLogics[0]);
					expect(storedRule.logicId).to.equal('logic-single-field-copy');
					expect(storedRule.sourceFieldName).to.equal('role');
					expect(storedRule.sourceNodeId).to.be.a('string').and.not.be.empty;
					expect(node.descendant?.name).to.equal('logicsSrc');
					expect(node.descendant?.children?.nodes ?? []).to.have.length(0);
				});

				assertContentIntegrityClean({startNode: formPath});
			});
		});
	});
});
