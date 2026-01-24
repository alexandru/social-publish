# UI Changes - Visual Description

## Before (Old Design)

```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│  [Add image] Button                                     │
│                                                         │
└─────────────────────────────────────────────────────────┘

After clicking "Add image":

┌─────────────────────────────────────────────────────────┐
│ Image (1)                                               │
│                                                         │
│ ┌──────────────────────────┐                           │
│ │ [Choose an image file...] │ Button                    │
│ └──────────────────────────┘                           │
│                                                         │
│ The image will be displayed in the post.               │
│                                                         │
│ Filename: (empty)                                       │
│                                                         │
│ Alt text for image (1)                                  │
│ ┌─────────────────────────────────────────────────┐   │
│ │                                                 │   │
│ └─────────────────────────────────────────────────┘   │
│                                                         │
│ [🗑 Remove] Button                                      │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## After (New Design)

```
┌─────────────────────────────────────────────────────────┐
│                                                         │
│  [➕ Add image] Button                                  │
│  (Opens file dialog immediately when clicked)           │
│                                                         │
└─────────────────────────────────────────────────────────┘

After selecting a file, a card appears:

┌─────────────────────────────────────────────────────────┐
│ ┌───────────────────────────────────────────────────┐ │
│ │ ┌────────┐  File: my-photo.jpg                    │ │
│ │ │        │                                         │ │
│ │ │ [IMG]  │  Alt text                               │ │
│ │ │ 80x80  │  ┌────────────────────────┐            │ │
│ │ │        │  │ Describe this image... │      [🗑]  │ │
│ │ └────────┘  └────────────────────────┘            │ │
│ │                                                    │ │
│ └───────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘

With multiple images:

┌─────────────────────────────────────────────────────────┐
│ ┌───────────────────────────────────────────────────┐ │
│ │ ┌────────┐  File: sunset.jpg                      │ │
│ │ │        │                                         │ │
│ │ │ [IMG]  │  Alt text                               │ │
│ │ │ 80x80  │  ┌────────────────────────┐            │ │
│ │ │        │  │ Beautiful sunset...    │      [🗑]  │ │
│ │ └────────┘  └────────────────────────┘            │ │
│ └───────────────────────────────────────────────────┘ │
│                                                         │
│ ┌───────────────────────────────────────────────────┐ │
│ │ ┌────────┐  File: cat.png                         │ │
│ │ │        │                                         │ │
│ │ │ [IMG]  │  Alt text                               │ │
│ │ │ 80x80  │  ┌────────────────────────┐            │ │
│ │ │        │  │ My cat sleeping...     │      [🗑]  │ │
│ │ └────────┘  └────────────────────────┘            │ │
│ └───────────────────────────────────────────────────┘ │
│                                                         │
│  [➕ Add image] Button                                  │
│  (Can add up to 4 images total)                        │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## Key Visual Improvements

### 1. Direct File Dialog
- **Before**: Two clicks required (Add Image → Choose file)
- **After**: One click (Add Image opens dialog immediately)

### 2. Image Preview
- **Before**: No preview, only filename as text
- **After**: 80x80px thumbnail with rounded corners and border
  - Image scales to fit within bounds
  - Gray background for contrast
  - Professional appearance

### 3. Card-Based Layout
- **Before**: Vertical stacked fields, lots of whitespace
- **After**: Compact horizontal card layout
  - Preview on left
  - Info in middle (filename + alt text)
  - Remove button on right
  - Clean borders and spacing

### 4. Better Visual Hierarchy
- **Before**: All elements same visual weight
- **After**: Clear sections with cards
  - Each image is a distinct visual unit
  - Easy to scan multiple images
  - Remove button clearly associated with each image

### 5. Responsive Design
- Uses Bulma's columns system
- `is-mobile` class ensures columns work on mobile
- `is-narrow` for fixed-width elements (preview, remove button)
- Flexible middle column for text content

## Design Details

### Color Scheme
- Cards: White background with `#dbdbdb` border
- Preview box: `#f5f5f5` background
- Remove button: Bulma danger color (red)
- Add button: Bulma info color (blue)

### Typography
- Labels: `is-small` class for compact appearance
- Placeholder text: "Describe this image for accessibility..."
- Clear, readable font sizes

### Spacing
- Card margin: `mb-4` (1.5rem)
- Card padding: `p-4` (1.5rem)
- Consistent internal spacing using Bulma utilities

### Interactive Elements
- All buttons have hover states (Bulma default)
- File input is hidden but accessible
- Alt text field is standard textarea
- Remove button has trash icon + tooltip

## Accessibility

- Alt text field remains prominent
- Clear labels for all inputs
- Keyboard navigation works
- Screen reader friendly (proper labels)
- File input accepts only images (`accept="image/*"`)

## Technical Implementation

### FileReader API
```javascript
const reader = FileReader()
reader.onload = (event) => {
    imagePreviewUrl = event.target.result  // Base64 data URL
}
reader.readAsDataURL(file)
```

### Hidden Input Pattern
```html
<input type="file" id="hidden-input" style="display: none" />
<button onclick="document.getElementById('hidden-input').click()">
    Add image
</button>
```

### Responsive Layout
```html
<div class="columns is-mobile">
    <div class="column is-narrow"><!-- Preview --></div>
    <div class="column"><!-- Info --></div>
    <div class="column is-narrow"><!-- Remove --></div>
</div>
```
