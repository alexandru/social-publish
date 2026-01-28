package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*

/**
 * Stateless text input field with label.
 *
 * @param label The label text
 * @param value Current value
 * @param onValueChange Callback when value changes
 * @param type Input type (default: Text)
 * @param placeholder Optional placeholder text
 * @param required Whether the field is required
 * @param disabled Whether the field is disabled
 * @param pattern Optional regex pattern for validation
 * @param id Optional HTML id for the input element (auto-generated if not provided)
 */
@Composable
fun TextInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    type: InputType<String> = InputType.Text,
    placeholder: String? = null,
    required: Boolean = false,
    disabled: Boolean = false,
    pattern: String? = null,
    id: String? = null,
) {
    val inputId = id ?: remember { "input-${kotlin.random.Random.nextLong()}" }

    Div(attrs = { classes("field") }) {
        Label(
            attrs = {
                classes("label")
                attr("for", inputId)
            }
        ) {
            Text(label)
        }
        Div(attrs = { classes("control") }) {
            Input(
                type = type,
                attrs = {
                    classes("input")
                    id(inputId)
                    value(value)
                    onInput { event -> onValueChange(event.value) }
                    if (placeholder != null) attr("placeholder", placeholder)
                    if (required) attr("required", "")
                    if (disabled) attr("disabled", "")
                    if (pattern != null) attr("pattern", pattern)
                },
            )
        }
    }
}

/**
 * Stateless textarea field with label.
 *
 * @param label The label text
 * @param value Current value
 * @param onValueChange Callback when value changes
 * @param rows Number of visible text rows
 * @param placeholder Optional placeholder text
 * @param required Whether the field is required
 * @param disabled Whether the field is disabled
 * @param id Optional HTML id for the textarea element (auto-generated if not provided)
 */
@Composable
fun TextAreaField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    rows: Int = 4,
    placeholder: String? = null,
    required: Boolean = false,
    disabled: Boolean = false,
    id: String? = null,
) {
    val textareaId = id ?: remember { "textarea-${kotlin.random.Random.nextLong()}" }

    Div(attrs = { classes("field") }) {
        Label(
            attrs = {
                classes("label")
                attr("for", textareaId)
            }
        ) {
            Text(label)
        }
        Div(attrs = { classes("control") }) {
            TextArea(
                attrs = {
                    classes("textarea")
                    id(textareaId)
                    attr("rows", rows.toString())
                    value(value)
                    onInput { event ->
                        val target = event.target
                        onValueChange(target.value)
                    }
                    if (placeholder != null) attr("placeholder", placeholder)
                    if (required) attr("required", "")
                    if (disabled) attr("disabled", "")
                }
            )
        }
    }
}

/**
 * Stateless checkbox field with label.
 *
 * @param label The label text
 * @param checked Whether the checkbox is checked
 * @param onCheckedChange Callback when checked state changes
 * @param disabled Whether the checkbox is disabled
 * @param helpContent Optional help content displayed below the checkbox
 */
@Composable
fun CheckboxField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    disabled: Boolean = false,
) {
    Label(attrs = { classes("checkbox") }) {
        Input(
            type = InputType.Checkbox,
            attrs = {
                checked(checked)
                onInput { event ->
                    val target = event.target
                    onCheckedChange(target.checked)
                }
                if (disabled) attr("disabled", "")
            },
        )
        Text(" $label")
    }
}

/**
 * Stateless service checkbox.
 *
 * @param serviceName The name of the service
 * @param checked Whether the checkbox is checked
 * @param onCheckedChange Callback when checked state changes
 * @param disabled Whether the checkbox is disabled
 */
@Composable
fun ServiceCheckboxField(
    serviceName: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    disabled: Boolean = false,
) {
    CheckboxField(
        label = serviceName,
        checked = checked,
        onCheckedChange = onCheckedChange,
        disabled = disabled,
    )
}
