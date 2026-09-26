/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ui.shared.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.configuration.navigation.ContentScaleType
import org.smartregister.fhircore.engine.configuration.navigation.ICON_TYPE_LOCAL
import org.smartregister.fhircore.engine.configuration.navigation.ICON_TYPE_REMOTE
import org.smartregister.fhircore.engine.configuration.navigation.ImageConfig
import org.smartregister.fhircore.engine.configuration.navigation.ImageType
import org.smartregister.fhircore.engine.configuration.view.ImageProperties
import org.smartregister.fhircore.engine.configuration.view.ImageShape
import org.smartregister.fhircore.engine.domain.model.ResourceData
import org.smartregister.fhircore.engine.domain.model.ViewType
import org.smartregister.fhircore.engine.ui.theme.DangerColor
import org.smartregister.fhircore.engine.ui.theme.SideMenuTopItemDarkColor
import org.smartregister.fhircore.engine.util.annotation.PreviewWithBackgroundExcludeGenerated
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.parseColor
import org.smartregister.fhircore.engine.util.extension.retrieveResourceId
import org.smartregister.fhircore.quest.ui.main.components.SIDE_MENU_ICON
import org.smartregister.fhircore.quest.util.extensions.conditional
import org.smartregister.fhircore.quest.util.extensions.handleClickEvent

const val SIDE_MENU_ITEM_LOCAL_ICON_TEST_TAG = "sideMenuItemLocalIconTestTag"
const val SIDE_MENU_ITEM_REMOTE_ICON_TEST_TAG = "sideMenuItemBinaryIconTestTag"

@Composable
fun Image(
  modifier: Modifier = Modifier,
  paddingEnd: Int? = null,
  tint: Color? = null,
  imageProperties: ImageProperties = ImageProperties(viewType = ViewType.IMAGE, size = 24),
  navController: NavController,
  resourceData: ResourceData? = null,
  decodeImage: ((String) -> Bitmap?)?,
) {
  val imageConfig = imageProperties.imageConfig
  val colorTint =
    tint
      ?: imageProperties.tint?.takeIf { it.isNotEmpty() }?.parseColor()
      ?: imageProperties.imageConfig?.color?.parseColor()
      ?: Color.White // Default color
  val context = LocalContext.current
  if (imageConfig != null) {
    if (imageProperties.text != null) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = imageProperties.text!!,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(end = 8.dp),
          color = imageProperties.textColor?.parseColor() ?: SideMenuTopItemDarkColor,
        )
        ClickableImageIcon(
          imageProperties = imageProperties,
          tint = colorTint,
          paddingEnd = paddingEnd,
          navController = navController,
          resourceData = resourceData,
          modifier = modifier,
          context = context,
          decodeImage = decodeImage,
        )
      }
    } else {
      ClickableImageIcon(
        imageProperties = imageProperties,
        tint = colorTint,
        paddingEnd = paddingEnd,
        navController = navController,
        resourceData = resourceData,
        modifier = modifier,
        context = context,
        decodeImage = decodeImage,
      )
    }
  }
}

@Composable
fun ClickableImageIcon(
  modifier: Modifier = Modifier,
  imageProperties: ImageProperties,
  tint: Color,
  paddingEnd: Int?,
  navController: NavController,
  resourceData: ResourceData? = null,
  context: Context? = null,
  decodeImage: ((String) -> Bitmap?)?,
) {
  Box(
    contentAlignment = Alignment.Center,
    modifier =
      modifier
        .conditional(
          imageProperties.shape != null,
          { clip(imageProperties.shape!!.composeShape) },
          { clip(RoundedCornerShape(imageProperties.borderRadius.dp)) },
        )
        .conditional(
          imageProperties.size != null,
          { size(if (imageProperties.size!! >= 22) imageProperties.size!!.dp else 16.dp) },
          { size(20.dp) },
        )
        .conditional(
          !imageProperties.backgroundColor.isNullOrEmpty(),
          { background(imageProperties.backgroundColor.parseColor()) },
        )
        .conditional(imageProperties.padding >= 0, { padding(imageProperties.padding.dp) })
        .conditional(
          imageProperties.clickable.toBoolean() && imageProperties.visible.toBoolean(),
          {
            clickable(
              onClick = {
                imageProperties.actions.handleClickEvent(
                  navController = navController,
                  resourceData = resourceData,
                  context = context,
                )
              },
            )
          },
        ),
  ) {
    val imageConfig =
      imageProperties.imageConfig?.interpolate(
        resourceData?.computedValuesMap ?: emptyMap(),
      )
    if (imageConfig != null) {
      when (imageConfig.type) {
        ICON_TYPE_LOCAL -> {
          LocalContext.current.retrieveResourceId(imageConfig.reference)?.let { drawableId ->
            Icon(
              modifier =
                Modifier.testTag(SIDE_MENU_ITEM_LOCAL_ICON_TEST_TAG)
                  .conditional(paddingEnd != null, { padding(end = paddingEnd?.dp!!) })
                  .align(Alignment.Center)
                  .fillMaxSize(0.9f),
              painter = painterResource(id = drawableId),
              contentDescription = SIDE_MENU_ICON,
              tint = tint,
            )
          }
        }
        ICON_TYPE_REMOTE -> {
          val imageType = imageConfig.imageType
          val colorFilter =
            if (imageType == ImageType.SVG || imageType == ImageType.PNG) tint else null
          val contentScale = convertContentScaleTypeToContentScale(imageConfig.contentScale)
          val decodedImage =
            imageConfig.reference?.extractLogicalIdUuid()?.let { decodeImage?.invoke(it) }
          if (decodedImage != null) {
            Image(
              modifier =
                Modifier.testTag(SIDE_MENU_ITEM_REMOTE_ICON_TEST_TAG)
                  .conditional(paddingEnd != null, { padding(end = paddingEnd?.dp!!) })
                  .align(Alignment.Center)
                  .fillMaxSize(0.9f),
              bitmap = decodedImage.asImageBitmap(),
              contentDescription = null,
              alpha = imageConfig.alpha,
              contentScale = contentScale,
              colorFilter = colorFilter?.let { ColorFilter.tint(it) },
            )
          }
        }
      }
    }
  }
}

fun convertContentScaleTypeToContentScale(
  contentScale: ContentScaleType,
): ContentScale {
  return when (contentScale) {
    ContentScaleType.FIT -> ContentScale.Fit
    ContentScaleType.CROP -> ContentScale.Crop
    ContentScaleType.FILLHEIGHT -> ContentScale.Crop
    ContentScaleType.INSIDE -> ContentScale.Inside
    ContentScaleType.NONE -> ContentScale.None
    ContentScaleType.FILLBOUNDS -> ContentScale.FillBounds
  }
}

@PreviewWithBackgroundExcludeGenerated
@Composable
fun ImagePreview() {
  Image(
    modifier = Modifier,
    imageProperties =
      ImageProperties(
        imageConfig = ImageConfig(ICON_TYPE_LOCAL, "ic_walk"),
        backgroundColor = "successColor",
        size = 80,
        shape = ImageShape.CIRCLE,
      ),
    tint = DangerColor.copy(0.1f),
    resourceData = ResourceData("id", ResourceType.Patient, emptyMap()),
    navController = rememberNavController(),
    decodeImage = null,
  )
}

@PreviewWithBackgroundExcludeGenerated
@Composable
fun ClickableImageWithTextPreview() {
  Image(
    modifier = Modifier,
    imageProperties =
      ImageProperties(
        imageConfig = ImageConfig(ICON_TYPE_LOCAL, "ic_copy", color = "#FFF000"),
        backgroundColor = Color.White.toString(),
        size = 24,
        shape = ImageShape.RECTANGLE,
        clickable = "true",
        visible = "true",
        text = "Click on the icon to copy your text",
      ),
    resourceData = ResourceData("id", ResourceType.Patient, emptyMap()),
    navController = rememberNavController(),
    decodeImage = null,
  )
}
