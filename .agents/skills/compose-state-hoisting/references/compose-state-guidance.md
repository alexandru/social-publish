# Compose State Quick Reference

Sources:
- https://developer.android.com/develop/ui/compose/state  
- https://developer.android.com/develop/ui/compose/state-hoisting  
- https://developer.android.com/develop/ui/compose/state-saving  
- https://developer.android.com/develop/ui/compose/state-lifespans  
- https://developer.android.com/develop/ui/compose/state-callbacks

## Table of Contents
- [Decision Tree: Where to Hoist State?](#decision-tree-where-to-hoist-state)
- [Lifespan API Comparison](#lifespan-api-comparison)
- [State Hoisting Checklist](#state-hoisting-checklist)
- [Common Patterns](#common-patterns)
- [Examples](#examples)
- [Observable Conversion](#observable-conversion)
- [Quick Rules](#quick-rules)

## Decision Tree: Where to Hoist State?

```
What kind of state is this?
│
├─ Simple UI logic (single composable, unlikely to share)
│  → Keep internal OR hoist for reusability (prefer hoisting)
│
├─ Simple UI logic (shared or likely to be shared)
│  → Hoist to lowest common ancestor
│
├─ Complex UI logic (multiple coordinated states/events)
│  → Plain state holder class (remember)
│
├─ Business logic or screen state
│  → Screen-level state holder (Android: ViewModel)
│
└─ Needed by many distant descendants (theme, user session)
   → Consider CompositionLocal
```

## Lifespan API Comparison

| API | Survives | Same Instance? | Use For | Avoid For | Platform Notes |
|-----|----------|----------------|---------|-----------|----------------|
| `remember` | Recomposition only | ✓ Yes | Internal UI state, cached computations | User input | All platforms |
| `retain` | Recomposition + config changes | ✓ Yes | Non-serializable objects (flows, players, caches) | Platform lifecycle objects, already-remembered objects | All platforms; Android: not process death |
| `rememberSaveable` | Recomposition + config + process death | ✗ No (equal) | User input, transient UI state | Large objects, lists | Android: Bundle (~1MB); varies on other platforms |

## State Hoisting Checklist

- [ ] State hoisted to lowest common ancestor?
- [ ] State down, events up (unidirectional flow)?
- [ ] UI composables stateless with `value` + callbacks?
- [ ] Immutable state exposed (`State<T>`, not `MutableState<T>`)?
- [ ] Correct lifespan API chosen?
- [ ] Remember keys specified for conditional resets?
- [ ] Saved state minimal and serializable?

## Common Patterns

### Stateless Composable
```kotlin
@Composable
fun TextField(value: String, onValueChange: (String) -> Unit)
```

### State Holder (Plain Class)
```kotlin
class XState {
    private var _value by mutableStateOf(initial)
    val value: State<Type> = derivedStateOf { _value }
    fun onEvent() { _value = newValue }
}

@Composable
fun rememberXState() = remember { XState() }
```

### Remember with Keys
```kotlin
var state by remember(userId) { mutableStateOf(default) }
```

### Derived State
```kotlin
val total by remember(items) { derivedStateOf { items.sumOf { it.price } } }
```

### Snapshot Collections
```kotlin
val list = remember { mutableStateListOf<Item>() }
val map = remember { mutableStateMapOf<Key, Value>() }
```

## Examples

### Stateless + stateful pair (hoisting)
```kotlin
@Composable
fun Counter(count: Int, onIncrement: () -> Unit) {
    Column {
        Text("Count: $count")
        Button(onClick = onIncrement) {
            Text("Increment")
        }
    }
}

@Composable
fun CounterScreen() {
    var count by remember { mutableStateOf(0) }
    Counter(count = count, onIncrement = { count++ })
}
```

### Lowest common ancestor hoisting
```kotlin
@Composable
fun ConversationScreen(messages: List<Message>) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column {
        MessagesList(
            messages = messages,
            listState = listState,
            modifier = Modifier.weight(1f)
        )
        UserInput(
            onSend = { newMessage ->
                coroutineScope.launch {
                    listState.animateScrollToItem(messages.size)
                }
            }
        )
    }
}
```

### Plain state holder for complex UI logic
```kotlin
class FiltersState(
    initial: Filter = Filter.All,
) {
    private var _filter by mutableStateOf(initial)
    val filter: State<Filter> = derivedStateOf { _filter }

    fun setFilter(newFilter: Filter) {
        _filter = newFilter
    }
}

@Composable
fun rememberFiltersState(initial: Filter = Filter.All): FiltersState {
    return remember { FiltersState(initial) }
}
```

### Saved UI element state (Android ViewModel example)
```kotlin
// Android: ViewModel with SavedStateHandle for process death survival
class FormViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
    var query by savedStateHandle.saveable { mutableStateOf("") }
        private set

    fun updateQuery(value: String) {
        query = value
    }
}
// Multiplatform: Use platform-specific saved state mechanisms or libraries
// like Circuit's RetainedStateRegistry, or custom serialization solutions
```

### Remember with keys for controlled state resets
```kotlin
@Composable
fun UserProfile(userId: String) {
    // State resets when userId changes; uses immutable data structure
    var profile by remember(userId) { mutableStateOf(ProfileData()) }

    // Load user data for this userId
    LaunchedEffect(userId) {
        profile = fetchProfile(userId)
    }
}

data class ProfileData(
    val name: String = "",
    val bio: String = ""
)
```

### Derived state for computed values
```kotlin
@Composable
fun ShoppingCart(items: List<CartItem>) {
    // Recomputes only when items list changes
    val totalPrice by remember(items) { derivedStateOf { items.sumOf { it.price } } }
    val itemCount by remember(items) { derivedStateOf { items.size } }

    Text("Total: $$totalPrice ($itemCount items)")
}
```

### Snapshot state collections (platform-agnostic)
```kotlin
@Composable
fun TodoList() {
    // Observable list that triggers recomposition on mutations
    val todos = remember { mutableStateListOf<Todo>() }

    Button(onClick = { todos.add(Todo("New task")) }) {
        Text("Add Todo")
    }

    LazyColumn {
        items(todos) { todo ->
            TodoItem(todo, onRemove = { todos.remove(todo) })
        }
    }
}
```

## Observable Conversion

| Type | API | Platform |
|------|-----|----------|
| `Flow` | `collectAsState()` | All (always collects) |
| `Flow` | `collectAsStateWithLifecycle()` | Android (lifecycle-aware) |
| `LiveData` | `observeAsState()` | Android |
| Custom | `produceState { }` | All |

## Quick Rules

**State Values:**
- Prefer immutable data structures (data classes, immutable lists)
- Use single state with immutable data class over multiple separate states
- For collections: use snapshot state (`mutableStateListOf`, `mutableStateMapOf`) or immutable collections wrapped in `State`

**Hoisting:**
- Hoist together when states change from same events
- Over-hoisting > under-hoisting (safer but more recompositions)
- Never duplicate state across owners

**Lifecycle:**
- `remember` ≠ `retain` (mutually exclusive for same object)
- Don't retain platform lifecycle objects (Android: Activity, Context, ViewModel, etc.)
- Don't double-wrap: don't pass `State<T>` to another `remember`

**Saving:**
- Only save small, serializable, session-scoped data
- Save IDs, not full objects; rehydrate from data layer
- Android `SavedStateHandle`: UI element state only, not screen state

**Performance:**
- Use `derivedStateOf` for computed values
- Specify `remember` keys to control when state resets
- Prefer snapshot collections over regular mutable collections
