/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.systemui.lint

import com.android.SdkConstants.CLASS_CONTEXT
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.getParentOfType

/**
 * Checks if anyone is calling sendBroadcast / sendBroadcastAsUser on a Context (or subclasses) and
 * directs them to using com.android.systemui.broadcast.BroadcastSender instead.
 */
@Suppress("UnstableApiUsage")
class BroadcastSentViaContextDetector : Detector(), SourceCodeScanner {

    override fun getApplicableMethodNames(): List<String> {
        return listOf("sendBroadcast", "sendBroadcastAsUser")
    }

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        if (node.getParentOfType(UClass::class.java)?.qualifiedName ==
                "com.android.systemui.broadcast.BroadcastSender"
        ) {
            // Don't warn for class we want the developers to use.
            return
        }

        val evaluator = context.evaluator
        if (evaluator.isMemberInSubClassOf(method, CLASS_CONTEXT)) {
            context.report(
                    issue = ISSUE,
                    location = context.getNameLocation(node),
                    message = "`Context.${method.name}()` should be replaced with " +
                    "`BroadcastSender.${method.name}()`"
            )
        }
    }

    companion object {
        @JvmField
        val ISSUE: Issue =
            Issue.create(
                id = "BroadcastSentViaContext",
                briefDescription = "Broadcast sent via `Context` instead of `BroadcastSender`",
                // lint trims indents and converts \ to line continuations
                explanation = """
                        Broadcasts sent via `Context.sendBroadcast()` or \
                        `Context.sendBroadcastAsUser()` will block the main thread and may cause \
                        missed frames. Instead, use `BroadcastSender.sendBroadcast()` or \
                        `BroadcastSender.sendBroadcastAsUser()` which will schedule and dispatch \
                        broadcasts on a background worker thread.""",
                category = Category.PERFORMANCE,
                priority = 8,
                severity = Severity.WARNING,
                implementation =
                Implementation(BroadcastSentViaContextDetector::class.java, Scope.JAVA_FILE_SCOPE)
            )
    }
}
