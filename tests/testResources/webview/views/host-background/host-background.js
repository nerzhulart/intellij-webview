// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// The page is painted with the color the test gives the host, so any other color on screen while the
// view opens is a frame of the engine itself, not of the page.
const background = new URLSearchParams(location.search).get("background");
if (background) {
  document.documentElement.style.background = "#" + background;
}
window.addEventListener("load", () => {
  // Two animation frames: the first one with the page background has been presented by then.
  requestAnimationFrame(() => requestAnimationFrame(() => {
    window.__HOST_BACKGROUND_PAINTED__ = true;
  }));
});
