/*
 * Copyright (C) 2026 The Lujarch Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lujarch.settings.pages

/**
 * The lambda given to this parameter runs in a scope of the process and
 * outlasts the page that gave it (a proxy task goes on after its page was
 * closed or recreated; a page dialog's end runs after its page has gone): it
 * uses no page, no page's context, strings or views, only what it is given.
 * A function that hands its own parameter on to such a parameter marks that
 * parameter too. `tools/lint/lifecycle.py` checks the lambdas given to these
 * parameters.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class OutlivesPage

/**
 * The lambda given to this parameter builds a page dialog and runs again,
 * with the recreated page as its receiver, whenever the page is recreated
 * (dark mode, language, display density): it reaches the page only through
 * that receiver and builds with the dialog's context. What it captures from
 * outside is data of the dialog (ids, names, values, the state its fields
 * change), never the page that showed it: not that page's context, views,
 * preferences or strings, not `this@Page`, not a callback of the caller
 * that is not itself marked. `tools/lint/lifecycle.py` checks these lambdas.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class RebuiltWithPage
