package com.flicknames.service.service;

import com.flicknames.service.entity.GivenNameLink.Source;

/** Published when a source of given-name links changes (research saved or approved, compound list edited). */
public record GivenNameSourceChanged(Source source) {}
