import {BaseComponent} from '@jahia/cypress';
import {CheckboxInput} from './CheckboxInput';

/**
 * Checkbox group form element
 * Handles multiple checkboxes grouped together with a common legend
 */
export class CheckboxGroup extends BaseComponent {
	/**
	 * Get the legend element for this checkbox group
	 */
	getLegend(): Cypress.Chainable {
		return this.get().find('legend.fmdb-group-legend');
	}

	/**
	 * Get a specific checkbox by its label text
	 */
	getCheckbox(label: string): CheckboxInput {
		return new CheckboxInput(
			this.get()
				.find('.fmdb-group-items .fmdb-group-item')
				.contains('label', label)
				.prev('input[type="checkbox"]')
		);
	}

	/**
	 * Check all checkboxes in the group
	 */
	checkAll(): this {
		this.get().find('input[type="checkbox"]').check();
		return this;
	}

	/**
	 * Uncheck all checkboxes in the group
	 */
	uncheckAll(): this {
		this.get().find('input[type="checkbox"]').uncheck();
		return this;
	}

	/**
	 * Check specific checkboxes by their label texts
	 */
	checkByLabels(labels: string[]): this {
		labels.forEach(label => {
			this.getCheckbox(label).check();
		});
		return this;
	}

	shouldBeVisible(): this {
		this.get().should('be.visible');
		return this;
	}

	shouldHaveLegend(text: string): this {
		this.getLegend().should('contain', text);
		return this;
	}

	shouldBeRequired(): this {
		this.getLegend().find('.fmdb-required-indicator').should('exist');
		// ARIA allows no aria-required on a group: the group's validation says when no box is ticked
		this.get().should('not.have.attr', 'aria-required');
		return this;
	}

	shouldNotBeRequired(): this {
		this.getLegend().find('.fmdb-required-indicator').should('not.exist');
		return this;
	}

	getHelpText(): Cypress.Chainable {
		return this.get().find('.fmdb-form-help');
	}

	shouldHaveHelpText(text: string): this {
		this.getHelpText().should('be.visible').and('have.text', text);
		// The group fieldset must reference the help block for screen readers
		this.getHelpText().invoke('attr', 'id').then(helpId => {
			this.get().invoke('attr', 'aria-describedby').should('contain', helpId);
		});
		return this;
	}

	shouldNotHaveHelpText(): this {
		this.getHelpText().should('not.exist');
		return this;
	}
}
