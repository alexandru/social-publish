package socialpublish.frontend.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.dom.*

data class SelectOption(val text: String, val value: String?)

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
    label: String?,
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
        if (label != null)
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

/**
 * Stateless select input field with label and icon.
 *
 * @param label The label text for the select field
 * @param value Current selected value (null for default/placeholder)
 * @param onValueChange Callback when value changes
 * @param options List of SelectOption for options (preserves order)
 * @param icon Optional Font Awesome icon class (e.g., "fa-globe")
 * @param disabled Whether the field is disabled
 * @param required Whether the field is required
 * @param id Optional HTML id for the select element (auto-generated if not provided)
 */
@Composable
fun SelectInputField(
    label: String?,
    value: String?,
    onValueChange: (String?) -> Unit,
    options: List<SelectOption>,
    icon: String? = null,
    disabled: Boolean = false,
    required: Boolean = false,
    id: String? = null,
) {
    val selectId = id ?: remember { "select-${kotlin.random.Random.nextLong()}" }

    Div(attrs = { classes("field") }) {
        if (label != null)
            Label(
                attrs = {
                    classes("label")
                    attr("for", selectId)
                }
            ) {
                Text(label)
            }
        Div(
            attrs = {
                if (icon != null) {
                    classes("control", "has-icons-left")
                } else {
                    classes("control")
                }
            }
        ) {
            Div(attrs = { classes("select") }) {
                Select(
                    attrs = {
                        id(selectId)
                        onChange { event ->
                            val selectedValue = event.target.value
                            onValueChange(if (selectedValue.isEmpty()) null else selectedValue)
                        }
                        if (disabled) attr("disabled", "")
                        if (required) attr("required", "")
                    }
                ) {
                    options.forEach { option ->
                        Option(
                            value = option.value ?: "",
                            attrs = {
                                if (option.value == value) {
                                    attr("selected", "")
                                }
                            },
                        ) {
                            Text(option.text)
                        }
                    }
                }
            }
            if (icon != null) {
                Span(attrs = { classes("icon", "is-left") }) { I(attrs = { classes("fas", icon) }) }
            }
        }
    }
}
