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

package com.android.systemui.log.table

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Test

@SmallTest
class TableChangeTest : SysuiTestCase() {

    @Test
    fun setString_isString() {
        val underTest = TableChange()

        underTest.reset(timestamp = 100, columnPrefix = "", columnName = "fakeName")
        underTest.set("fakeValue")

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("fakeValue")
    }

    @Test
    fun setString_null() {
        val underTest = TableChange()

        underTest.reset(timestamp = 100, columnPrefix = "", columnName = "fakeName")
        underTest.set(null as String?)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun setBoolean_isBoolean() {
        val underTest = TableChange()

        underTest.reset(timestamp = 100, columnPrefix = "", columnName = "fakeName")
        underTest.set(true)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("true")
    }

    @Test
    fun setInt_isInt() {
        val underTest = TableChange()

        underTest.reset(timestamp = 100, columnPrefix = "", columnName = "fakeName")
        underTest.set(8900)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("8900")
    }

    @Test
    fun setInt_null() {
        val underTest = TableChange()

        underTest.reset(timestamp = 100, columnPrefix = "", columnName = "fakeName")
        underTest.set(null as Int?)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun setThenReset_isEmpty() {
        val underTest = TableChange()

        underTest.reset(timestamp = 100, columnPrefix = "", columnName = "fakeName")
        underTest.set(8900)
        underTest.reset(timestamp = 0, columnPrefix = "prefix", columnName = "name")

        assertThat(underTest.hasData()).isFalse()
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun getName_hasPrefix() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")

        assertThat(underTest.getName()).contains("fakePrefix")
        assertThat(underTest.getName()).contains("fakeName")
    }

    @Test
    fun getName_noPrefix() {
        val underTest = TableChange(columnPrefix = "", columnName = "fakeName")

        assertThat(underTest.getName()).contains("fakeName")
    }

    @Test
    fun resetThenSet_hasNewValue() {
        val underTest = TableChange()

        underTest.reset(timestamp = 100, columnPrefix = "prefix", columnName = "original")
        underTest.set("fakeValue")
        underTest.reset(timestamp = 0, columnPrefix = "", columnName = "updated")
        underTest.set(8900)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("updated")
        assertThat(underTest.getName()).doesNotContain("prefix")
        assertThat(underTest.getName()).doesNotContain("original")
        assertThat(underTest.getVal()).isEqualTo("8900")
    }

    @Test
    fun updateTo_emptyToString_isString() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set("newString")
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("newString")
    }

    @Test
    fun updateTo_intToEmpty_isEmpty() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(42)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isFalse()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("null")
    }

    @Test
    fun updateTo_stringToBool_isBool() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set("oldString")

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set(true)
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("true")
    }

    @Test
    fun updateTo_intToString_isString() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(43)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set("newString")
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("newString")
    }

    @Test
    fun updateTo_boolToInt_isInt() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(false)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set(44)
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("44")
    }

    @Test
    fun updateTo_boolToNewBool_isNewBool() {
        val underTest = TableChange(columnPrefix = "fakePrefix", columnName = "fakeName")
        underTest.set(false)

        val new = TableChange(columnPrefix = "newPrefix", columnName = "newName")
        new.set(true)
        underTest.updateTo(new)

        assertThat(underTest.hasData()).isTrue()
        assertThat(underTest.getName()).contains("newPrefix")
        assertThat(underTest.getName()).contains("newName")
        assertThat(underTest.getVal()).isEqualTo("true")
    }
}
