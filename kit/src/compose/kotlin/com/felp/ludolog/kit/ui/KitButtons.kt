package com.felp.ludolog.kit.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape

/*
 * Botones con las esquinas del tema. Los de Material 3 son siempre de pildora (no miran las
 * formas del tema), y en Ludolog los tres temas son de esquina recta: con estos se ven igual.
 */

@Composable
fun LButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Look.shape,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = Button(onClick, modifier, enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)

@Composable
fun LOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Look.shape,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) = OutlinedButton(onClick, modifier, enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)

@Composable
fun LTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = Look.shape,
    colors: ButtonColors = ButtonDefaults.textButtonColors(),
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit,
) = TextButton(onClick, modifier, enabled, shape = shape, colors = colors, contentPadding = contentPadding, content = content)
