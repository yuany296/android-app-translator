"use strict";
/**
 * 覆盖层验收用的最小 DOM 桩（2026-09-29）。
 *
 * 目的不是复刻浏览器，而是让 `app/src/main/assets/mt-overlay-renderer.js` 能**真的**
 * 跑一遍建节点/挂载/同步的路径，从而把 §13 里「结构相关」的断言（层级顺序、遮罩倾角、
 * 实底填充范围、晚绑定清理图、裁切收边、不重建）变成可执行的 Node 测试。
 *
 * 明确不模拟：CSS 层叠/继承、真实文字测量、布局引擎。
 * 因此字号相关断言只覆盖「区间推导」（resolveFontSizeRange），不覆盖探针二分结果。
 *
 * 文字测量相关：`__mtStubProbeScroll` 允许测试强制探针溢出，走「缩字号」分支。
 */

const VIEWPORT = { left: 0, top: 0, width: 400, height: 800 };

function kebab(name) {
  return String(name).replace(/[A-Z]/g, (ch) => "-" + ch.toLowerCase());
}

function createStyle() {
  const props = new Map();
  const target = {
    setProperty(name, value) { props.set(String(name), String(value)); },
    getPropertyValue(name) { return props.has(String(name)) ? props.get(String(name)) : ""; },
    removeProperty(name) { props.delete(String(name)); },
    get cssText() { return Array.from(props, ([k, v]) => `${k}:${v}`).join(";"); }
  };
  return new Proxy(target, {
    get(obj, key) {
      if (key in obj) return obj[key];
      if (typeof key === "string") return props.has(kebab(key)) ? props.get(kebab(key)) : "";
      return undefined;
    },
    set(obj, key, value) {
      if (typeof key === "string" && !(key in obj)) {
        props.set(kebab(key), String(value));
        return true;
      }
      obj[key] = value;
      return true;
    },
    has(obj, key) { return key in obj || (typeof key === "string" && props.has(kebab(key))); }
  });
}

function matchesSimple(el, selector) {
  const text = String(selector || "").trim();
  if (!text) return false;
  if (text.startsWith(".")) {
    const name = text.slice(1);
    return String(el._className || "").split(/\s+/).includes(name);
  }
  if (text.startsWith("#")) return el._attrs.id === text.slice(1);
  const attr = /^\[([\w-]+)(?:="([^"]*)")?\]$/.exec(text);
  if (attr) {
    const value = el._attrs[attr[1]];
    if (value == null) return false;
    return attr[2] == null ? true : String(value) === attr[2];
  }
  return String(el.tagName || "").toLowerCase() === text.toLowerCase();
}

function matches(el, selector) {
  // 支持 `.a.b` 复合、逗号列表、简单属性选择器；不支持后代/子代组合器（渲染层没用到）。
  return String(selector)
    .split(",")
    .map((part) => part.trim())
    .filter(Boolean)
    .some((part) => part.split(/(?=[.#[])/).filter(Boolean).every((piece) => matchesSimple(el, piece)));
}

function makeDocument() {
  const doc = {};

  function createElement(tagName) {
    const el = {
      tagName: String(tagName || "div").toUpperCase(),
      nodeType: 1,
      parentElement: null,
      children: [],
      _className: "",
      _attrs: Object.create(null),
      _text: "",
      _rect: null,
      _computed: Object.create(null),
      _listeners: Object.create(null),
      _scrollHeight: 0,
      _clientHeight: 0,
      _scrollWidth: 0,
      _clientWidth: 0,
      style: createStyle(),
      dataset: Object.create(null)
    };

    Object.defineProperty(el, "className", {
      get() { return el._className; },
      set(value) { el._className = String(value == null ? "" : value); }
    });
    Object.defineProperty(el, "id", {
      get() { return el._attrs.id || ""; },
      set(value) { el._attrs.id = String(value); }
    });
    Object.defineProperty(el, "textContent", {
      get() {
        if (el.children.length === 0) return el._text;
        return el.children.map((child) => child.textContent).join("");
      },
      set(value) {
        el._text = String(value == null ? "" : value);
        el.children.forEach((child) => { child.parentElement = null; });
        el.children = [];
      }
    });
    Object.defineProperty(el, "childElementCount", { get() { return el.children.length; } });
    Object.defineProperty(el, "firstElementChild", { get() { return el.children[0] || null; } });
    Object.defineProperty(el, "isConnected", {
      get() {
        let node = el;
        while (node) {
          if (node === doc.documentElement) return true;
          node = node.parentElement;
        }
        return false;
      }
    });
    // 探针溢出注入：`__mtStubProbeScroll` 可以是 {width,height}（恒定溢出），
    // 也可以是 (probe) => {width,height} —— 后者能按探针当前字号给不同结果，
    // 用来验「二分收字号」这类需要随字号变化的分支（2026-09-30）。
    function probeScroll(el, axis) {
      const forced = globalThis.__mtStubProbeScroll;
      if (!forced || !el._className.includes("mt-measure-probe")) return null;
      const value = typeof forced === "function" ? forced(el) : forced;
      if (!value) return null;
      return value[axis];
    }
    Object.defineProperty(el, "scrollHeight", {
      get() {
        const forced = probeScroll(el, "height");
        return forced == null ? el._scrollHeight : forced;
      }
    });
    Object.defineProperty(el, "clientHeight", { get() { return el._clientHeight; } });
    Object.defineProperty(el, "scrollWidth", {
      get() {
        const forced = probeScroll(el, "width");
        return forced == null ? el._scrollWidth : forced;
      }
    });
    Object.defineProperty(el, "clientWidth", { get() { return el._clientWidth; } });

    el.classList = {
      contains(name) { return el._className.split(/\s+/).filter(Boolean).includes(name); },
      add(...names) {
        const set = new Set(el._className.split(/\s+/).filter(Boolean));
        names.forEach((name) => set.add(name));
        el._className = Array.from(set).join(" ");
      },
      remove(...names) {
        const set = new Set(el._className.split(/\s+/).filter(Boolean));
        names.forEach((name) => set.delete(name));
        el._className = Array.from(set).join(" ");
      },
      toggle(name, force) {
        const has = el.classList.contains(name);
        const next = force === undefined ? !has : Boolean(force);
        if (next) el.classList.add(name); else el.classList.remove(name);
        return next;
      }
    };

    el.appendChild = (child) => {
      if (!child) return child;
      if (child.parentElement) child.parentElement.removeChild(child);
      child.parentElement = el;
      el.children.push(child);
      return child;
    };
    el.removeChild = (child) => {
      const at = el.children.indexOf(child);
      if (at >= 0) {
        el.children.splice(at, 1);
        child.parentElement = null;
      }
      return child;
    };
    el.insertBefore = (child, reference) => {
      if (!child) return child;
      if (child.parentElement) child.parentElement.removeChild(child);
      let at = reference ? el.children.indexOf(reference) : -1;
      if (at < 0) at = el.children.length;
      el.children.splice(at, 0, child);
      child.parentElement = el;
      return child;
    };
    el.remove = () => { if (el.parentElement) el.parentElement.removeChild(el); };
    el.replaceWith = (other) => {
      const parent = el.parentElement;
      if (!parent) return;
      const at = parent.children.indexOf(el);
      if (at < 0) return;
      parent.children[at] = other;
      other.parentElement = parent;
      el.parentElement = null;
    };
    el.setAttribute = (name, value) => { el._attrs[name] = String(value); };
    el.getAttribute = (name) => (name in el._attrs ? el._attrs[name] : null);
    el.addEventListener = (type, handler) => {
      (el._listeners[type] = el._listeners[type] || []).push(handler);
    };
    el.dispatch = (type, event) => {
      (el._listeners[type] || []).forEach((handler) => handler(event || {}));
    };
    el.closest = (selector) => {
      let node = el;
      while (node) {
        if (node.nodeType === 1 && matches(node, selector)) return node;
        node = node.parentElement;
      }
      return null;
    };
    el.querySelectorAll = (selector) => {
      const found = [];
      const walk = (node) => {
        node.children.forEach((child) => {
          if (matches(child, selector)) found.push(child);
          walk(child);
        });
      };
      walk(el);
      return found;
    };
    el.querySelector = (selector) => el.querySelectorAll(selector)[0] || null;
    el.getBoundingClientRect = () => {
      if (el._rect) return Object.assign({}, el._rect);
      if (el.parentElement) return el.parentElement.getBoundingClientRect();
      return { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 };
    };
    el._setRect = (rect) => {
      const left = Number(rect.left) || 0;
      const top = Number(rect.top) || 0;
      const width = Number(rect.width) || 0;
      const height = Number(rect.height) || 0;
      el._rect = { left, top, width, height, right: left + width, bottom: top + height };
    };
    el._setComputed = (values) => { Object.assign(el._computed, values); };
    return el;
  }

  doc.createElement = createElement;
  doc.documentElement = createElement("html");
  doc.documentElement._setRect(VIEWPORT);
  doc.head = createElement("head");
  doc.body = createElement("body");
  doc.documentElement.appendChild(doc.head);
  doc.documentElement.appendChild(doc.body);
  doc.images = [];
  doc.querySelectorAll = (selector) => doc.documentElement.querySelectorAll(selector);
  doc.querySelector = (selector) => doc.documentElement.querySelector(selector);
  doc.getElementById = (id) => {
    const found = doc.documentElement.querySelectorAll(`#${id}`);
    return found[0] || null;
  };
  return doc;
}

function install() {
  const doc = makeDocument();
  const win = {
    requestAnimationFrame() { return 1; },
    cancelAnimationFrame() {},
    setTimeout() { return 0; },
    clearTimeout() {},
    getComputedStyle(node) {
      const computed = (node && node._computed) || {};
      return {
        position: computed.position || "static",
        overflowX: computed.overflowX || "visible",
        overflowY: computed.overflowY || "visible"
      };
    }
  };
  globalThis.window = win;
  globalThis.document = doc;
  globalThis.getComputedStyle = win.getComputedStyle;
  if (typeof globalThis.URL === "undefined") globalThis.URL = require("url").URL;
  if (typeof globalThis.URL.createObjectURL !== "function") {
    let seq = 0;
    globalThis.URL.createObjectURL = () => `blob:stub/${(seq += 1)}`;
    globalThis.URL.revokeObjectURL = () => {};
  }
  return { document: doc, window: win };
}

module.exports = { install, makeDocument, createStyle, matches, VIEWPORT };
