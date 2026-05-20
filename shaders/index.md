---
layout: page
title: Shaders
permalink: /shaders/
---

<style>
  .shader-list { list-style: none; padding: 0; margin: 1.2rem 0; }
  .shader-list li { margin: 0; }
  .shader-list a {
    display: block;
    padding: 0.9rem 1rem;
    border-bottom: 1px solid currentColor;
    text-decoration: none;
    font-size: 1.05rem;
    line-height: 1.3;
  }
  .shader-list a:first-child { border-top: 1px solid currentColor; }
  .shader-list a:active { opacity: 0.6; }
  @media (max-width: 600px) {
    .shader-list a { padding: 1.1rem 0.6rem; font-size: 1.1rem; }
  }
</style>

<ul class="shader-list">
  <li><a href="{{ site.baseurl }}/shaders/red/">Red — simplest shader</a></li>
  <li><a href="{{ site.baseurl }}/shaders/simple/">Simple — UV gradient</a></li>
  <li><a href="{{ site.baseurl }}/shaders/pewdiepie-wave/">PewDiePie wave</a></li>
  <li><a href="{{ site.baseurl }}/shaders/lava/">Lava</a></li>
</ul>
