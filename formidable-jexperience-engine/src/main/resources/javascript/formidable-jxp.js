/*
 * Formidable, jExperience integration: the client half.
 *
 * Two jobs, both driven by the JSON block the render filter writes before each form:
 *
 * 1. Sends the `form` event of an accepted submission through jExperience's tracker, after the
 *    server answered 200 and with the values the server accepted, never the DOM fields. The form
 *    island announces an accepted submission with a bubbling `formidable:submitted` DOM event; the
 *    server put the accepted values in the `jexperience` block of its answer.
 * 2. Prefills the mapped fields from the visitor's profile. Before the tracker starts, the union of
 *    every block's `prefill` properties is pushed once into `digitalDataOverrides`, so that the one
 *    context request the tracker makes anyway returns them; once the context is loaded AND the form
 *    island has taken the form over (`formidable:ready`), the values are written into the fields.
 *
 * Design and gates: docs/architecture/jexperience-integration.md ("Rendering and prefill",
 * "Submitting", "The send condition").
 *
 * One instance per page whatever the number of forms: every form declares the same static asset and
 * core keeps one of them in the head. The guard below is the belt to that braces — a page built
 * another way, or a second copy loaded by hand, still gets one instance.
 */
(function () {
  "use strict";

  if (window.formidableJxp) {
    return;
  }
  const api = {};
  window.formidableJxp = api;

  const BLOCK_SELECTOR = 'script[type="application/json"][data-formidable-jxp]';
  const READY_EVENT = "formidable:ready";
  const SUBMITTED_EVENT = "formidable:submitted";
  // The island says when the form goes back to the state the visitor found it in: a reset, or the new
  // form it offers after a submission. Both empty it, so the prefill is due again — the page opens anew
  // for it.
  const RESET_EVENT = "formidable:reset";
  const NEW_FORM_EVENT = "formidable:newForm";

  /** The configuration block the render filter wrote for the form, or null. */
  api.configOf = (formId) => {
    const block = document.querySelector(BLOCK_SELECTOR + '[data-formidable-jxp="' + formId + '"]');
    return block ? parse(block, formId) : null;
  };

  /** Every configuration block of the page, parsed; a broken one is reported and skipped. */
  api.configs = () =>
    Array.from(document.querySelectorAll(BLOCK_SELECTOR))
      .map((block) => parse(block, block.getAttribute("data-formidable-jxp")))
      .filter(Boolean);

  function parse(block, formId) {
    try {
      return JSON.parse(block.textContent);
    } catch (e) {
      // the render filter wrote the block, so a broken one is a bug worth seeing
      console.warn(
        "[Formidable] the jExperience configuration of form " + formId + " is not valid JSON",
        e,
      );
      return null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // The gates: every reason to talk to the tracker or not, in one place — the one part of
  // Formidable that depends on the tracker's API.

  /**
   * `wem` is jExperience's tracker; a consent manager that blocked it leaves none. `wemLoaded` is set
   * once the tracker ran its callbacks — also in its fallback mode, where no context was loaded, so
   * the loaded context must carry a profile. `activateWem` false is the visitor's own "disable
   * tracking". Whoever decides whether the tracker starts at all — a consent tool, jExperience's
   * bot filter — decides for this script too: no tracker, nothing sent, nothing prefilled.
   */
  api.trackerReady = () => {
    const wem = window.wem;
    const init = (window.digitalData && window.digitalData.wemInitConfig) || {};
    const context =
      wem && typeof wem.getLoadedContext === "function" ? wem.getLoadedContext() : null;
    return (
      Boolean(wem) &&
      window.wemLoaded === true &&
      Boolean(context && context.profileId) &&
      init.activateWem !== false
    );
  };

  /**
   * Sending needs, on top, that the form is tracked, and `getFormNamesToWatch()` is the whole answer:
   * the tracker fills it from the `formEventCondition`s of the loaded context, and a form the author
   * mapped has one — the rule this integration writes at publication. Goals and segments are rules
   * too, so one list covers both and the page has nothing to declare about it.
   * `disableTrackedConditionsListeners` is the integrator's page-level "no automatic form tracking",
   * honoured for sending and never set here; prefill reads, it does not track, so it ignores it.
   */
  api.shouldCollect = (formId) => {
    const wem = window.wem;
    const init = (window.digitalData && window.digitalData.wemInitConfig) || {};
    return (
      api.trackerReady() &&
      !init.disableTrackedConditionsListeners &&
      typeof wem.getFormNamesToWatch === "function" &&
      wem.getFormNamesToWatch().indexOf(formId) > -1
    );
  };

  // ---------------------------------------------------------------------------------------------
  // Sending

  /**
   * The `form` event: the tracker's own builder, the form named for dashboards, the accepted values
   * as fields.
   */
  api.buildEvent = (config, fields) => {
    const event = window.wem.buildFormEvent(config.formId);
    event.target.properties = { name: config.name, path: config.path };
    event.flattenedProperties = { fields: fields || {} };
    return event;
  };

  document.addEventListener(SUBMITTED_EVENT, (e) => {
    const detail = e.detail || {};
    const block = (detail.response || {}).jexperience;
    if (!block || block.formId !== detail.formId) {
      return; // the server did not describe this submission for jExperience
    }
    const config = api.configOf(detail.formId);
    if (!config || !api.shouldCollect(detail.formId)) {
      return;
    }
    // No success callback: nothing of the page depends on the event having landed.
    window.wem.collectEvent(api.buildEvent(config, block.fields), undefined, (xhr) => {
      console.warn(
        "[Formidable] jExperience did not collect the form event: " +
          (xhr && xhr.status) +
          " " +
          (xhr && xhr.statusText),
      );
    });
  });

  // ---------------------------------------------------------------------------------------------
  // Prefill, part one: ask the tracker for the properties, once for the whole page.
  //
  // jExperience creates `window.digitalDataOverrides` in the head and hands the array to the tracker
  // at `wem.init()`; the tracker merges its entries at DOMContentLoaded, concatenating arrays. This
  // script is deferred, so it runs after the whole document is parsed and before DOMContentLoaded:
  // every block is in front of it and the array is still to be read — one push covers every form.

  /** The profile properties the page's forms prefill from, each once. */
  api.prefillProperties = () => {
    const names = [];
    api.configs().forEach((config) => {
      Object.keys(config.prefill || {}).forEach((field) => {
        const property = config.prefill[field] && config.prefill[field].property;
        if (property && names.indexOf(property) === -1) {
          names.push(property);
        }
      });
    });
    return names;
  };

  const required = api.prefillProperties();
  if (required.length > 0) {
    if (Array.isArray(window.digitalDataOverrides)) {
      window.digitalDataOverrides.push({ wemInitConfig: { requiredProfileProperties: required } });
    } else {
      // the block is written for a tracked site only, so the array should be there: said once, or a page
      // that never prefills would have nothing to show for it
      console.warn(
        "[Formidable] jExperience's digitalDataOverrides is not on the page: the profile properties " +
          required.join(", ") +
          " cannot be requested and nothing will be prefilled",
      );
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Prefill, part two: write the values, once per form, when both the tracker and the island are ready.

  /** Dispatched on a hidden control after it is written: an island whose state that control mirrors takes the value from here. */
  const PREFILL_EVENT = "formidable:prefill";

  /** The form the block describes: named by its UUID since phase 3, and taken over by the island once `noValidate` is set. */
  api.formOf = (formId) => document.querySelector('form[name="' + formId + '"]');

  api.islandReady = (form) => Boolean(form && form.noValidate);

  /**
   * The controls the visitor touched, marked by the browser's own `input` and `change` events — trusted
   * ones, never the events a script dispatches, this one included. Comparing the live state with the
   * default state would not say it: a single select has its first option selected by the browser
   * itself, a colour input is born "#000000" over an empty attribute, and a field the visitor typed in
   * then cleared reads as untouched again.
   */
  const touched = new WeakSet();
  const watch = (form) => {
    if (!form || form.dataset.fmdbWatched) {
      return;
    }
    form.dataset.fmdbWatched = "true";
    const mark = (e) => {
      if (e.isTrusted && e.target) {
        touched.add(e.target);
      }
    };
    form.addEventListener("input", mark, true);
    form.addEventListener("change", mark, true);
    // A reset empties the form, so nothing is prefilled any more: one listener for the form, whatever
    // the number of fields the prefill locked or hid, and the island's formidable:reset — which waits
    // for the values to be back — is what tells the prefill to run again.
    form.addEventListener("reset", () => undoPrefill(form));
  };
  api.configs().forEach((config) => watch(api.formOf(config.formId)));

  /**
   * Fills the mapped fields of the form from the loaded context, one field at a time, and once. A control
   * the visitor touched is left alone. A default value the author gave the field gives way: the profile is
   * the fresher word about this visitor. A field the profile has no value for is left as it is, default
   * included — the prefill never blanks. A field that was written is then left editable, made read-only or
   * hidden, as the author asked (`then`). Nothing happens without a tracker, a profile and a hydrated form;
   * a form is marked done once something was written into it, so the second of the two signals that open
   * the gates does not write again.
   */
  api.prefill = (formId) => {
    const form = api.formOf(formId);
    const config = api.configOf(formId);
    const pairs = (config && config.prefill) || {};
    if (!form || form.dataset.fmdbPrefilled || Object.keys(pairs).length === 0) {
      return false;
    }
    if (!api.islandReady(form) || !api.trackerReady()) {
      return false;
    }
    watch(form);
    const values = window.wem.getLoadedContext().profileProperties || {};
    const written = Object.keys(pairs).filter((field) => {
      const entry = pairs[field];
      const done = fill(form, field, values[entry.property], entry.then);
      if (done && entry.then) {
        after(form, field, entry.then);
      }
      return done;
    });
    if (written.length > 0) {
      form.dataset.fmdbPrefilled = "true";
    }
    return true;
  };

  /**
   * Writes one profile value into the controls named `field`, by their shape; true when something was
   * written. `input` then `change` are dispatched on what changed, so the conditional logic, the input
   * mask and any validation see the value as if the visitor had typed it. `then` travels to the island a
   * hidden control may belong to, which applies it itself.
   */
  function fill(form, field, value, then) {
    const controls = Array.prototype.slice.call(form.elements).filter((el) => el.name === field);
    if (controls.length === 0) {
      return false;
    }
    const first = controls[0];
    const type = (first.type || "").toLowerCase();
    if (type === "checkbox" || type === "radio") {
      return fillChoices(controls, value);
    }
    if (first.tagName === "SELECT") {
      return fillSelect(first, value);
    }
    if (type === "file" || type === "submit" || type === "button" || type === "reset") {
      return false;
    }
    return fillText(first, value, then);
  }

  /**
   * One writable value out of what the context holds: a multi-valued property is an array, an empty one
   * says nothing, and an object is not a value — `String([])` or `String({})` would put a word in the
   * visitor's field.
   */
  function scalar(value) {
    if (Array.isArray(value)) {
      const found = value.find((v) => scalar(v) !== null);
      return found === undefined ? null : scalar(found);
    }
    if (value === undefined || value === null || value === "" || typeof value === "object") {
      return null;
    }
    return value;
  }

  /** The values a choice control matches against: every scalar of an array, or the one value, as strings. */
  function list(value) {
    const values = Array.isArray(value) ? value : [value];
    return values.map(scalar).filter((v) => v !== null).map(String);
  }

  const pad = (n) => (n < 10 ? "0" : "") + n;

  /**
   * The calendar day of an ISO date-time in the visitor's own zone — jCustomer stores instants and a date
   * input takes a day, and the UTC day of an instant is one day off for half the planet. A bare day is
   * kept as it is; a text that is no date at all is not written.
   */
  function localDate(value) {
    const text = String(value);
    if (/^\d{4}-\d{2}-\d{2}$/.test(text)) {
      return text;
    }
    const date = new Date(text);
    return isNaN(date.getTime())
      ? null
      : date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
  }

  /** The same, to the minute, for a datetime-local input. */
  function localDateTime(value) {
    const text = String(value);
    if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(text)) {
      return text;
    }
    const date = new Date(text);
    return isNaN(date.getTime())
      ? null
      : localDate(text) + "T" + pad(date.getHours()) + ":" + pad(date.getMinutes());
  }

  /**
   * text, email, tel, url, number, colour, date, datetime-local, textarea, hidden: one string. A hidden
   * control may be the mirror of an island's state (the range slider is built that way): it is written
   * like any other, then told, and the island takes the value over — or lets its state reset it.
   */
  function fillText(control, value, then) {
    if (touched.has(control)) {
      return false;
    }
    const one = scalar(value);
    let text = one === null ? null : String(one);
    if (text !== null && control.type === "date") {
      text = localDate(text);
    } else if (text !== null && control.type === "datetime-local") {
      text = localDateTime(text);
    }
    if (text === null) {
      return false;
    }
    setValue(control, text);
    changed(control);
    if (control.type === "hidden") {
      control.dispatchEvent(
        new CustomEvent(PREFILL_EVENT, { bubbles: true, detail: { value: text, then: then } }),
      );
    }
    return true;
  }

  /**
   * A checkbox group, a single checkbox (a boolean or its own value), radios: check what the value names,
   * and only that. A profile naming nothing the options carry — no value at all, or values the field does
   * not offer — writes nothing, so an option the author checked by default stays checked: the prefill never
   * blanks a choice. A lone checkbox is a boolean too: an explicit false unchecks it, an absent value leaves it.
   * Reports a write whenever the profile named a value, as the other shapes do — a choice the profile merely
   * confirms is prefilled all the same, and gets what the author asked to follow.
   */
  function fillChoices(controls, value) {
    if (controls.some((c) => touched.has(c))) {
      return false;
    }
    const wanted = list(value);
    const single = controls.length === 1 && controls[0].type === "checkbox";
    const asBoolean = single && (value === true || value === false || value === "true" || value === "false");
    const matching = controls.filter((c) => wanted.indexOf(c.value) > -1);
    if (matching.length === 0 && !asBoolean) {
      return false;
    }
    controls.forEach((control) => {
      const check = asBoolean ? value === true || value === "true" : matching.indexOf(control) > -1;
      if (control.checked !== check) {
        control.checked = check;
        changed(control);
      }
    });
    return true;
  }

  /** A select, single or multiple: select the options whose value the profile names; no match, nothing. */
  function fillSelect(select, value) {
    const options = Array.prototype.slice.call(select.options);
    if (touched.has(select)) {
      return false;
    }
    const wanted = list(value);
    const matching = options.filter((o) => wanted.indexOf(o.value) > -1);
    if (matching.length === 0) {
      return false;
    }
    if (select.multiple) {
      options.forEach((o) => {
        o.selected = matching.indexOf(o) > -1;
      });
    } else {
      setValue(select, matching[0].value);
    }
    changed(select);
    return true;
  }

  /**
   * What the author asked once the profile's value is in the field. `readOnly` keeps it in sight and takes
   * the visitor's hand off it; `hidden` takes it out of sight, its value still submitted. Both write
   * `data-fmdb-prefilled` on the field's wrapper — the styling hook, and what the conditional logic reads to
   * keep a hidden one out of sight when a rule shows it again. Neither reaches a field the prefill left alone
   * (the caller only asks after a write): the visitor has to be able to fill it. A value the field's own
   * validation rejects is never hidden either — the visitor could neither see the error nor fix it. A field
   * whose named control is an island's hidden mirror is the island's to handle (see below).
   */
  function after(form, field, then) {
    const controls = Array.prototype.slice.call(form.elements).filter((el) => el.name === field);
    // A hidden control mirrors an island's state (the range slider): the write into it says nothing of
    // what the island accepted — a value out of its bounds, an answer the visitor already gave — and the
    // mirror is barred from constraint validation. The island was told through the event and applies the
    // choice itself, on its own verdict.
    if (controls.length === 0 || controls.some((c) => (c.type || "").toLowerCase() === "hidden")) {
      return;
    }
    const wrapper = controls[0].closest("[data-fmdb-node-name]");
    if (then === "readOnly") {
      const unlock = lock(controls);
      if (wrapper) {
        wrapper.dataset.fmdbPrefilled = "readonly";
      }
      onPrefill(form, () => {
        unlock();
        clearMarks(wrapper);
      });
    } else if (then === "hidden" && wrapper && controls.every((c) => c.checkValidity())) {
      wrapper.dataset.fmdbPrefilled = "hidden";
      wrapper.style.display = "none";
      wrapper.setAttribute("aria-hidden", "true");
      onPrefill(form, () => clearMarks(wrapper));
    }
  }

  /**
   * What each form's prefill has to give back, gathered as `after()` takes it: the locks, the marks, the
   * fields put out of sight. One list per form, run — and emptied — whenever the form goes back to what
   * the page opened with. A reset does that (a field kept out of sight would otherwise come back to its
   * default value behind a `display: none` wrapper, a required one then blocking a submission over an
   * error the visitor cannot see), and so does a prefill run again: without this the second `lock()` of a
   * field would read the read-only attribute the first one set and keep it for good.
   */
  const undos = new WeakMap();

  function onPrefill(form, undo) {
    const list = undos.get(form) || [];
    list.push(undo);
    undos.set(form, list);
  }

  function undoPrefill(form) {
    (undos.get(form) || []).forEach((undo) => undo());
    undos.set(form, []);
  }

  /** The wrapper as it was before the prefill: in sight, and saying nothing to the styling or the logic. */
  function clearMarks(wrapper) {
    if (!wrapper) {
      return;
    }
    delete wrapper.dataset.fmdbPrefilled;
    wrapper.style.removeProperty("display");
    wrapper.removeAttribute("aria-hidden");
  }

  /**
   * Read-only, by shape. A textual control has the attribute. A select, a radio, a checkbox or a colour input
   * has none (`readonly` applies to the textual types only), and `disabled` would take the value out of the
   * submission: they put the prefilled state back whenever the visitor changes it, and say the change again
   * on what they put back, so that a listener registered before this one — an island's validation, attached at
   * hydration — reads the restored state and not the visitor's transient one (the base stylesheet takes the
   * pointer off them too). `aria-readonly` goes where the role supports it: the checkbox and the select
   * themselves, and for radios the group — a `radiogroup`, which the views that render same-named radios
   * mark, with the field's own wrapper as the fallback; a colour input has no role to carry it. Returns
   * what gives the visitor their hand back, for the reset.
   */
  function lock(controls) {
    const first = controls[0];
    const type = (first.type || "").toLowerCase();
    if (first.tagName === "SELECT" || type === "checkbox" || type === "radio" || type === "color") {
      const snapshot = (c) =>
        c.tagName === "SELECT"
          ? Array.prototype.slice.call(c.options).map((o) => o.selected)
          : c.type === "color"
            ? c.value
            : c.checked;
      const state = controls.map(snapshot);
      const differs = (c, i) => JSON.stringify(snapshot(c)) !== JSON.stringify(state[i]);
      const restore = () => {
        controls.forEach((c, i) => {
          if (!differs(c, i)) {
            return; // also what stops the change said below from coming back here
          }
          if (c.tagName === "SELECT") {
            Array.prototype.slice.call(c.options).forEach((o, j) => {
              o.selected = state[i][j];
            });
          } else if (c.type === "color") {
            setValue(c, state[i]);
          } else {
            c.checked = state[i];
          }
          changed(c);
        });
      };
      const ariaHolders = [];
      controls.forEach((c) => {
        c.addEventListener("change", restore);
        if (c.tagName === "SELECT" || type === "checkbox") {
          c.setAttribute("aria-readonly", "true");
          ariaHolders.push(c);
        }
      });
      if (type === "radio") {
        // Only a radiogroup carries aria-readonly: a bare fieldset is a `group`, which does not, and a
        // one-choice radio has no fieldset at all — climbing to the nearest one would mark whatever
        // encloses the field, an author's fieldset of unrelated fields. The field's own wrapper is the
        // fallback: every shape has one, and it already carries data-fmdb-prefilled.
        const group =
          first.closest('[role="radiogroup"]') || first.closest("[data-fmdb-node-name]");
        if (group) {
          group.setAttribute("aria-readonly", "true");
          ariaHolders.push(group);
        }
      }
      return () => {
        controls.forEach((c) => c.removeEventListener("change", restore));
        ariaHolders.forEach((el) => el.removeAttribute("aria-readonly"));
      };
    }
    // A hidden mirror never reaches here: after() leaves an island's field to the island. The undo puts
    // the attribute back where it was, not to false: `readonly` is an author's property on a text or a
    // number field, and a reset must not hand the visitor a field the author had closed.
    const wasReadOnly = first.readOnly;
    first.readOnly = true;
    return () => {
      first.readOnly = wasReadOnly;
    };
  }

  /**
   * Through the prototype's setter, not the element's: React instruments the element's own `value`
   * setter to remember the last value it saw, and a value written through it looks unchanged to a
   * hydrated island; the prototype's setter writes the DOM and leaves the tracker to notice.
   */
  function setValue(control, text) {
    const proto = Object.getPrototypeOf(control);
    const descriptor = Object.getOwnPropertyDescriptor(proto, "value");
    if (descriptor && typeof descriptor.set === "function") {
      descriptor.set.call(control, text);
    } else {
      control.value = text;
    }
  }

  function changed(control) {
    control.dispatchEvent(new Event("input", { bubbles: true }));
    control.dispatchEvent(new Event("change", { bubbles: true }));
  }

  /** Tries every form of the page; each one fills once, when its two gates are open. */
  const attemptAll = () => api.configs().forEach((config) => api.prefill(config.formId));

  /**
   * The form is back to what the page opened with, so the prefill is due again: the visitor reset it, or
   * asked for another one after a submission. Everything the previous prefill did is given back first —
   * the locks, the marks, the fields put out of sight — so that a second run starts from an untouched
   * form and cannot read its own work as the author's (a `lock()` running over a locked field would
   * remember `readonly` as the author's and never give it back). Then two memories go, for that form
   * alone: the mark that says it was filled once, and what its controls remember of having been touched,
   * since what the visitor typed went with the reset. The rest is the first prefill — the same gates, the
   * same writes, the same `then`. Running it twice for one return changes nothing.
   */
  api.prefillAgain = (formId) => {
    const form = api.formOf(formId);
    if (!form) {
      return false;
    }
    undoPrefill(form);
    delete form.dataset.fmdbPrefilled;
    Array.prototype.slice.call(form.elements).forEach((control) => touched.delete(control));
    return api.prefill(formId);
  };

  [RESET_EVENT, NEW_FORM_EVENT].forEach((name) =>
    document.addEventListener(name, (e) => {
      const formId = e.detail && e.detail.formId;
      if (formId) {
        api.prefillAgain(formId);
      }
    }),
  );

  // The island signals its readiness; the tracker does not fire a DOM event, so its own callback
  // registration is used, which runs the callback at once when the context is already loaded. The
  // callbacks run in ascending priority and the tracker sets wemLoaded in its own at 99: ours comes
  // after, or the gate above would still read the flag as unset.
  document.addEventListener(READY_EVENT, (e) => {
    const formId = e.detail && e.detail.formId;
    if (formId) {
      api.prefill(formId);
    }
  });
  const onTrackerReady = (callback) => {
    if (window.wemLoaded === true) {
      callback();
    } else if (window.wem && typeof window.wem._registerCallback === "function") {
      window.wem._registerCallback(callback, "Formidable prefill", 100);
    } else {
      // a consent tool or a bot filter that kept the tracker off the page decides for this script too;
      // said once, so that a page that never prefills can be read
      console.warn(
        "[Formidable] no jExperience tracker on the page when the form script ran: the mapped fields are not prefilled",
      );
    }
  };
  if (required.length > 0) {
    onTrackerReady(attemptAll);
    attemptAll();
  }
})();
