import {BaseComponent, getComponentBySelector} from '@jahia/cypress';

class ContactMappingRow extends BaseComponent {
	static defaultSelector = '[data-sel-role="contact-mapping-row"]';
}

/**
 * The HubSpotContactMapping selector as rendered in Content Editor for the fieldMapping
 * property of fmdbhs:createContactAction. Each row lays out three dropdowns (HubSpot
 * field, source mode, form field) or two dropdowns plus an input for a constant.
 */
export class HubspotContactMappingField extends BaseComponent {
	static defaultSelector = '[data-sel-content-editor-field="fmdbhs:createContactAction_fieldMapping"]';

	private static readonly menuSelector = '.moonstone-menu:not(.moonstone-hidden)';
	private static readonly menuOverlaySelector = '.moonstone-menu_overlay';

	static readonly hubspotFieldDropdownIndex = 0;
	static readonly sourceDropdownIndex = 1;
	static readonly formFieldDropdownIndex = 2;

	private dismissOpenMenu(): void {
		cy.get('body').then($body => {
			const $overlay = $body.find(HubspotContactMappingField.menuOverlaySelector);
			if ($overlay.length > 0) {
				cy.wrap($overlay.first()).click('topLeft', {force: true});
				return;
			}

			if ($body.find(HubspotContactMappingField.menuSelector).length > 0) {
				cy.get('body').type('{esc}', {force: true});
			}
		});
		cy.get('body', {timeout: 15000}).should($body => {
			expect($body.find(HubspotContactMappingField.menuSelector).length).to.equal(0);
		});
	}

	waitUntilReady(): this {
		this.get().should('be.visible');
		this.get().find('.moonstone-loader', {timeout: 30000}).should('have.length', 0);
		// The editor panel is a fixed, scrolling container: a long mapping pushes the button below the fold.
		this.get().find('[data-sel-role="contact-mapping-add"]').scrollIntoView().should('be.visible');
		return this;
	}

	getRow(index: number): ContactMappingRow {
		return getComponentBySelector(ContactMappingRow, `${ContactMappingRow.defaultSelector}:eq(${index})`, this);
	}

	shouldHaveRowCount(count: number): this {
		if (count === 0) {
			this.get().find(ContactMappingRow.defaultSelector).should('have.length', 0);
		} else {
			this.get().find(ContactMappingRow.defaultSelector, {timeout: 15000}).should('have.length', count);
		}

		return this;
	}

	addRow(): this {
		this.get().find('[data-sel-role="contact-mapping-add"]').scrollIntoView().click();
		return this;
	}

	autoMap(): this {
		this.get().find('[data-sel-role="contact-mapping-automap"]').scrollIntoView().should('not.be.disabled').click();
		return this;
	}

	refreshHubspotFields(): this {
		this.get().find('[data-sel-role="contact-mapping-refresh"]').scrollIntoView().should('not.be.disabled').click();
		this.waitUntilReady();
		return this;
	}

	removeRow(index: number): this {
		this.getRow(index).get().find('[data-sel-role="contact-mapping-remove"]').click();
		return this;
	}

	openDropdown(rowIndex: number, dropdownIndex: number): this {
		this.dismissOpenMenu();
		this.getRow(rowIndex).get().find('.moonstone-dropdown_container').eq(dropdownIndex).scrollIntoView().click();
		// eslint-disable-next-line cypress/no-unnecessary-waiting
		cy.wait(500);
		this.getRow(rowIndex).get().find('.moonstone-dropdown_container').eq(dropdownIndex)
			.find(HubspotContactMappingField.menuSelector, {timeout: 15000}).should('be.visible');
		return this;
	}

	selectMenuItem(label: string): this {
		cy.get(HubspotContactMappingField.menuSelector).contains('.moonstone-menuItem', label).trigger('click');
		return this;
	}

	menuShouldHaveItems(labels: string[]): this {
		for (const label of labels) {
			cy.get(HubspotContactMappingField.menuSelector).contains('.moonstone-menuItem', label).scrollIntoView();
			cy.get(HubspotContactMappingField.menuSelector).contains('.moonstone-menuItem', label).should('be.visible');
		}

		return this;
	}

	menuShouldNotHaveItems(labels: string[]): this {
		cy.get(HubspotContactMappingField.menuSelector).find('.moonstone-menuItem').then($items => {
			const itemLabels = Array.from($items, item => item.textContent?.trim() ?? '');
			for (const label of labels) {
				expect(itemLabels.some(text => text.startsWith(label)), `menu must not offer ${label}`).to.equal(false);
			}
		});
		return this;
	}

	closeMenu(): this {
		this.dismissOpenMenu();
		return this;
	}

	selectHubspotField(rowIndex: number, label: string): this {
		this.openDropdown(rowIndex, HubspotContactMappingField.hubspotFieldDropdownIndex);
		this.selectMenuItem(label);
		return this;
	}

	selectSource(rowIndex: number, label: string): this {
		this.openDropdown(rowIndex, HubspotContactMappingField.sourceDropdownIndex);
		this.selectMenuItem(label);
		return this;
	}

	selectFormField(rowIndex: number, label: string): this {
		this.openDropdown(rowIndex, HubspotContactMappingField.formFieldDropdownIndex);
		this.selectMenuItem(label);
		return this;
	}

	typeConstant(rowIndex: number, value: string): this {
		this.getRow(rowIndex).get().find('input').first().clear();
		this.getRow(rowIndex).get().find('input').first().type(value);
		return this;
	}

	rowShouldContainText(rowIndex: number, text: string): this {
		this.getRow(rowIndex).get().should('contain.text', text);
		return this;
	}

	warningsShouldContain(text: string): this {
		this.get().find('[data-sel-role="contact-mapping-warnings"]', {timeout: 15000}).should('contain.text', text);
		return this;
	}

	warningsShouldNotContain(text: string): this {
		this.get().then($field => {
			expect($field.find('[data-sel-role="contact-mapping-warnings"]').text()).not.to.contain(text);
		});
		return this;
	}

	statusShouldContain(text: string): this {
		this.get().should('contain.text', text);
		return this;
	}
}
