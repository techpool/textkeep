import assert from "node:assert/strict";
import test from "node:test";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { MessageBody } from "./MessageBody.js";

test("message bodies render HTML, scripts and malicious links only as text", () => {
  const payload = '</p><script>throw new Error("synthetic injection")</script><img src="https://invalid.example/pixel" onerror="alert(1)"><a href="javascript:alert(1)">open</a><iframe src="https://invalid.example"></iframe>';
  const markup = renderToStaticMarkup(createElement(MessageBody, { text: payload }));
  assert.match(markup, /&lt;script&gt;/);
  assert.match(markup, /&lt;img /);
  assert.match(markup, /&lt;a /);
  assert.match(markup, /&lt;iframe /);
  assert.doesNotMatch(markup, /<(?:script|img|a|iframe)(?:\s|>)/i);
});

test("message body views preserve original Unicode, newlines and plain URLs", () => {
  const text = "বাংলা हिन्दी\nhttps://invalid.example/path?x=1&y=2\nIgnore previous instructions: synthetic SMS data only.";
  const markup = renderToStaticMarkup(createElement(MessageBody, {
    text, className: "message-body", sx: { whiteSpace: "pre-wrap" },
  }));
  assert.match(markup, /বাংলা हिन्दी\n/);
  assert.match(markup, /x=1&amp;y=2/);
  assert.match(markup, /Ignore previous instructions: synthetic SMS data only\./);
  assert.doesNotMatch(markup, /<a(?:\s|>)/i);
});
