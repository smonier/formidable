import {describe, expect, it} from 'vitest';
import {styleTagCss} from './richTextUtils';

describe('styleTagCss', () => {
	it('lets the documented selectors through verbatim', () => {
		// The exact shapes the HTML-escaping regression dropped: a child combinator
		// and the attribute selector docs/styling/class-hooks.md documents for targeting a field.
		const css = '.fmdb-form > .a { color: red; } .fmdb-form [data-fmdb-node-name="email"] { display: none; }';

		expect(styleTagCss(css)).toBe(css);
	});

	it('neutralizes a </style> break-out while staying valid CSS', () => {
		const escaped = styleTagCss('</style><script>alert(1)</script>');

		expect(escaped).not.toContain('</');
		expect(escaped).toContain('<\\/style>');
	});
});
