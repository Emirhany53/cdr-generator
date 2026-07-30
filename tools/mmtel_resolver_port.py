"""
AsnTypeRegistryBuilder + AsnFieldTreeResolver'in (fix'ler dahil) sadik Python
portu. Amac: gercek Java kodunu derleyip calistiramadigimiz icin (sandboxta
JDK 21 yok), semanin TUM alanlari icin encoder'in URETMESI GEREKEN tag
seklini (extra universal SEQUENCE/SET sarmali var mi yok mu) hesaplayip,
gercek .ber dosyasindaki byte'larla kapsamli sekilde karsilastirmak.

Bu port asagidaki Java kaynaklarini birebir izler:
  AsnTypeRegistryBuilder.java, AsnFieldTreeResolver.java (resolveRoot,
  resolveByTypeName, parseFieldLines, attachChildren, isChoiceType,
  isSetType, effectiveExplicit, isVerifiedSetNeutralizationFamily),
  BerEncoderService.java (wrapInTlv/encodeRepeated karar agaci - sekil
  duzeyinde, deger encode etmeden).
"""
import re
from dataclasses import dataclass, field as dc_field
from typing import Optional

# --------------------------------------------------------------------------
# AsnTypeRegistryBuilder port
# --------------------------------------------------------------------------

DEFINITION_START = re.compile(r'([A-Za-z][\w-]*)\s*::=')
STRUCTURED_KIND = re.compile(r'(SEQUENCE|SET|CHOICE|ENUMERATED)')
MODULE_HEADER_KEYWORD = "DEFINITIONS"
TAGS_KEYWORD = "TAGS"
TAGGING_MODE_PATTERN = re.compile(MODULE_HEADER_KEYWORD + r'\s+(IMPLICIT|EXPLICIT|AUTOMATIC)\s+' + TAGS_KEYWORD)
ANONYMOUS_CHOICE_FIELD = re.compile(r'([A-Za-z][\w-]*)\s+CHOICE\s*\{')


@dataclass
class TypeDef:
    type_name: str
    kind: str  # SEQUENCE | SET | CHOICE | ENUMERATED | ALIAS
    raw_body: Optional[str] = None
    alias_target: Optional[str] = None


def strip_line_comments(contents: str) -> str:
    contents = re.sub(r'(?s)/\*.*?\*/', ' ', contents)
    contents = re.sub(r'(?m)^\s*END\s*$', ' ', contents)
    out_lines = []
    for line in re.split(r'\r?\n', contents):
        idx = line.find('--')
        out_lines.append(line[:idx] if idx >= 0 else line)
    return '\n'.join(out_lines) + '\n'


def find_matching_brace_end(text: str, brace_index: int) -> int:
    depth = 0
    for i in range(brace_index, len(text)):
        c = text[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i
    return len(text) - 1


def extract_balanced_body(statement: str, brace_index: int) -> str:
    depth = 0
    body_start = brace_index + 1
    for i in range(brace_index, len(statement)):
        c = statement[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return statement[body_start:i]
    return statement[body_start:]


def extract_anonymous_choices(parent_type_name: str, body: str, registry: dict) -> str:
    result = []
    last_end = 0
    for m in ANONYMOUS_CHOICE_FIELD.finditer(body):
        choice_brace_index = m.end() - 1
        choice_body = extract_balanced_body(body, choice_brace_index)
        choice_end = find_matching_brace_end(body, choice_brace_index)
        field_name = m.group(1)
        synthetic_name = f"{parent_type_name}${field_name}"
        registry.setdefault(synthetic_name, TypeDef(synthetic_name, 'CHOICE', raw_body=choice_body))
        result.append(body[last_end:m.start(1)])
        result.append(f"{field_name} {synthetic_name}")
        last_end = choice_end + 1
    result.append(body[last_end:])
    return ''.join(result)


def parse_statement(type_name: str, statement: str, registry: dict) -> TypeDef:
    kind_m = STRUCTURED_KIND.search(statement)
    brace_index = statement.find('{')
    if kind_m and brace_index != -1 and kind_m.start() < brace_index:
        kind = kind_m.group(1)
        body = extract_balanced_body(statement, brace_index)
        if kind in ('SEQUENCE', 'SET'):
            body = extract_anonymous_choices(type_name, body, registry)
        return TypeDef(type_name, kind, raw_body=body)
    return TypeDef(type_name, 'ALIAS', alias_target=statement.strip())


def build_registry(contents: str) -> dict:
    registry = {}
    if not contents or not contents.strip():
        return registry
    cleaned = strip_line_comments(contents)

    names, name_starts, body_starts = [], [], []
    for m in DEFINITION_START.finditer(cleaned):
        preceding = cleaned[:m.start(1)].strip()
        candidate_name = m.group(1)
        is_module_header = preceding.endswith(MODULE_HEADER_KEYWORD) or candidate_name == MODULE_HEADER_KEYWORD
        is_tagging_mode = candidate_name == TAGS_KEYWORD and MODULE_HEADER_KEYWORD in preceding
        if is_module_header or is_tagging_mode:
            continue
        names.append(candidate_name)
        name_starts.append(m.start())
        body_starts.append(m.end())

    for i in range(len(names)):
        statement_end = name_starts[i + 1] if i + 1 < len(names) else len(cleaned)
        statement = cleaned[body_starts[i]:statement_end]
        if names[i] not in registry:
            registry[names[i]] = parse_statement(names[i], statement, registry)
    return registry


def detect_tagging_mode(contents: str) -> str:
    if not contents or not contents.strip():
        return 'EXPLICIT'
    m = TAGGING_MODE_PATTERN.search(strip_line_comments(contents))
    if m:
        return m.group(1)
    return 'EXPLICIT'

# --------------------------------------------------------------------------
# AsnFieldTreeResolver port (including both EXPLICIT-neutralization fixes)
# --------------------------------------------------------------------------

FIELD_LINE = re.compile(
    r'^\s*([A-Za-z_][\w-]*)\s*(?:\[\s*(?:(UNIVERSAL|APPLICATION|PRIVATE)\s+)?(\d+)\s*\]\s*)?'
    r'(EXPLICIT\s+|IMPLICIT\s+)?(.+?)\s*,?\s*$'
)
ALIAS_TAG = re.compile(
    r'^\s*\[\s*(?:(UNIVERSAL|APPLICATION|PRIVATE)\s+)?(\d+)\s*\]\s*(EXPLICIT\s+|IMPLICIT\s+)?'
)
SIZE_CONSTRAINT = re.compile(r'SIZE\s*\(\s*(\d+)\s*(?:\.\.\s*(\d+)\s*)?\)')
MAX_DEPTH = 15


@dataclass
class AsnField:
    field_name: str
    field_type: str
    optional: bool = False
    repeated: bool = False
    choice: bool = False
    set_: bool = False
    tag_number: Optional[int] = None
    tag_class: str = 'CONTEXT'
    explicit: bool = False
    universal_tag_override: Optional[int] = None
    children: list = dc_field(default_factory=list)


def strip_constraint(text: str) -> str:
    if text is None:
        return None
    current = text
    while True:
        nxt = re.sub(r'\([^()]*\)', '', current).strip()
        if nxt == current:
            return current
        current = nxt


def is_repeated_expression(type_expr: str) -> bool:
    return type_expr.startswith('SEQUENCE OF') or type_expr.startswith('SET OF')


def extract_repeated_inner_type(type_expr: str) -> str:
    return re.sub(r'^(SEQUENCE|SET)\s+OF\s+', '', type_expr).strip()


def strip_alias_tag(type_expr: Optional[str]) -> Optional[str]:
    if type_expr is None:
        return None
    return ALIAS_TAG.sub('', type_expr, count=1).strip()


def normalize_alias_target(alias_target: Optional[str]) -> Optional[str]:
    return strip_alias_tag(strip_constraint(alias_target))


def read_universal_tag(type_expr: Optional[str]) -> Optional[int]:
    """Port of AsnFieldTreeResolver.readUniversalTag."""
    if type_expr is None:
        return None
    m = ALIAS_TAG.match(type_expr)
    if not m or m.group(1) != 'UNIVERSAL':
        return None
    return int(m.group(2))


def resolve_universal_tag_override(registry: dict, type_name: Optional[str]) -> Optional[int]:
    """Port of AsnFieldTreeResolver.resolveUniversalTagOverride.

    Follows the alias chain looking for a type that re-tags itself into the
    UNIVERSAL class, e.g. GraphicStringImp ::= [UNIVERSAL 25] IMPLICIT IA5String.
    Returns the tag number the wire format must carry, or None (the usual case)
    when the primitive's own universal tag applies.
    """
    current = strip_alias_tag(type_name)
    if current and is_repeated_expression(current):
        current = extract_repeated_inner_type(current)
    guard = set()
    while current is not None and current not in guard:
        guard.add(current)
        definition = registry.get(current)
        if definition is None or definition.kind != 'ALIAS' or definition.alias_target is None:
            return None
        alias_target = definition.alias_target
        universal_tag = read_universal_tag(alias_target)
        if universal_tag is not None:
            return universal_tag
        if contains_named_number_list(alias_target):
            return None
        target = strip_alias_tag(strip_constraint(alias_target))
        current = extract_repeated_inner_type(target) if is_repeated_expression(target) else target
    return None


def contains_named_number_list(type_expr: Optional[str]) -> bool:
    return bool(type_expr) and '{' in type_expr and NAMED_NUMBER_ENTRY.search(type_expr) is not None


NAMED_NUMBER_ENTRY = re.compile(r'([A-Za-z][\w-]*)\s*\(\s*(-?\d+)\s*\)')


def normalize_type_text(t: str) -> str:
    return re.sub(r'\s+', ' ', t).strip()


def parse_field_line(line: str, tagging_mode: str) -> Optional[AsnField]:
    optional = 'OPTIONAL' in line
    m = FIELD_LINE.search(line)
    if not m:
        return None
    field_name = m.group(1)
    tag_class = m.group(2) if m.group(2) else 'CONTEXT'
    tag_number = int(m.group(3)) if m.group(3) else None
    tagging_keyword = m.group(4).strip() if m.group(4) else None
    explicit = resolve_explicit(tagging_keyword, tagging_mode)

    raw_type_expr = m.group(5)
    type_expr = strip_constraint(raw_type_expr).replace('OPTIONAL', '').strip()
    repeated = is_repeated_expression(type_expr)
    field_type = extract_repeated_inner_type(type_expr) if repeated else normalize_type_text(type_expr)

    return AsnField(field_name=field_name, field_type=field_type, optional=optional,
                     repeated=repeated, tag_number=tag_number, tag_class=tag_class, explicit=explicit)


def resolve_explicit(tagging_keyword: Optional[str], tagging_mode: str) -> bool:
    if tagging_keyword and tagging_keyword.upper() == 'EXPLICIT':
        return True
    if tagging_keyword and tagging_keyword.upper() == 'IMPLICIT':
        return False
    return tagging_mode == 'EXPLICIT'


def split_field_entries(raw_body: str) -> list:
    entries = []
    current = []
    depth = 0
    for c in raw_body:
        if c in '([{':
            depth += 1
            current.append(c)
        elif c in ')]}':
            depth -= 1
            current.append(c)
        elif c in '\n,' and depth == 0:
            entry = ''.join(current).strip()
            if entry:
                entries.append(entry)
            current = []
        else:
            current.append(c)
    last = ''.join(current).strip()
    if last:
        entries.append(last)
    return entries


def is_alias_repeated(registry: dict, type_name: str) -> bool:
    d = registry.get(type_name)
    if d is None or d.kind != 'ALIAS' or d.alias_target is None:
        return False
    return is_repeated_expression(strip_constraint(strip_alias_tag(d.alias_target)))


def is_choice_type(registry: dict, type_name: str) -> bool:
    current = strip_constraint(type_name)
    guard = set()
    while current is not None and current not in guard:
        guard.add(current)
        d = registry.get(current)
        if d is None:
            return False
        if d.kind == 'CHOICE':
            return True
        if d.kind != 'ALIAS' or d.alias_target is None:
            return False
        target = strip_constraint(strip_alias_tag(d.alias_target))
        current = extract_repeated_inner_type(target) if is_repeated_expression(target) else target
    return False


def is_set_type(registry: dict, type_name: str) -> bool:
    current = strip_constraint(type_name)
    guard = set()
    while current is not None and current not in guard:
        guard.add(current)
        d = registry.get(current)
        if d is None:
            return False
        if d.kind == 'SET':
            return True
        if d.kind != 'ALIAS' or d.alias_target is None:
            return False
        target = strip_constraint(strip_alias_tag(d.alias_target))
        current = extract_repeated_inner_type(target) if is_repeated_expression(target) else target
    return False


def is_verified_set_neutralization_family(registry: dict) -> bool:
    involved_party = registry.get('InvolvedParty')
    return involved_party is not None and involved_party.kind == 'CHOICE'


def effective_explicit(registry: dict, written_explicit: bool, repeated: bool,
                        choice_element: bool) -> bool:
    scalar_choice = choice_element and not repeated
    if scalar_choice:
        return written_explicit
    if is_verified_set_neutralization_family(registry):
        return False
    return written_explicit


def resolve_effective_tag(registry: dict, tag_number, tag_class, explicit, inner_type: str, tagging_mode: str):
    """Mirrors resolveEffectiveTag - only used by attachChildren (CHOICE alternatives)."""
    if tag_number is not None:
        return tag_number, tag_class, explicit
    alias_def = registry.get(inner_type)
    if alias_def is None or alias_def.kind != 'ALIAS' or alias_def.alias_target is None:
        return None, tag_class, explicit
    m = ALIAS_TAG.search(alias_def.alias_target)
    if not m:
        return None, tag_class, explicit
    found_tag_class = m.group(1) if m.group(1) else 'CONTEXT'
    found_explicit = (m.group(3).strip().upper() == 'EXPLICIT') if m.group(3) else (tagging_mode == 'EXPLICIT')
    return int(m.group(2)), found_tag_class, found_explicit


MAX_RESOLVE_DEPTH = 15


def resolve_by_type_name(registry: dict, type_name: Optional[str], visiting: set, depth: int,
                          cache: dict, tagging_mode: str) -> list:
    if type_name is None:
        return []
    cache_key = type_name
    if cache_key in cache:
        return cache[cache_key]
    definition = registry.get(type_name)
    if definition is None:
        return []
    if type_name in visiting or depth > MAX_RESOLVE_DEPTH:
        return []
    next_visiting = set(visiting)
    next_visiting.add(type_name)

    if definition.kind == 'ENUMERATED':
        result = []
    elif definition.kind == 'ALIAS':
        result = resolve_alias(registry, definition, next_visiting, depth, cache, tagging_mode)
    elif definition.kind in ('SEQUENCE', 'SET'):
        result = parse_field_lines(registry, definition.raw_body, next_visiting, depth, cache, tagging_mode)
    elif definition.kind == 'CHOICE':
        result = resolve_choice_alternative(registry, type_name, definition.raw_body, next_visiting, depth,
                                             cache, tagging_mode)
    else:
        result = []
    cache[cache_key] = result
    return result


def resolve_alias(registry, definition, visiting, depth, cache, tagging_mode):
    target = normalize_alias_target(definition.alias_target)
    inner_type = extract_repeated_inner_type(target) if is_repeated_expression(target) else target
    return resolve_by_type_name(registry, inner_type, visiting, depth + 1, cache, tagging_mode)


def resolve_choice_alternative(registry, choice_type_name, raw_body, visiting, depth, cache, tagging_mode):
    first_alt = None
    for line in split_field_entries(raw_body):
        trimmed = line.strip()
        if not trimmed:
            continue
        alt = parse_field_line(trimmed, tagging_mode)
        if alt is None:
            continue
        if first_alt is None:
            first_alt = alt
            break  # default selection = first alternative (no choiceSelections support needed here)
    if first_alt is not None:
        return [attach_children(registry, first_alt, visiting, depth, cache, tagging_mode)]
    return []


def attach_children(registry, field: AsnField, visiting, depth, cache, tagging_mode):
    inner_type = strip_constraint(field.field_type)
    repeated = field.repeated or is_alias_repeated(registry, inner_type)
    children = resolve_by_type_name(registry, inner_type, visiting, depth + 1, cache, tagging_mode)

    tag_number, tag_class, explicit_tag = resolve_effective_tag(
        registry, field.tag_number, field.tag_class, field.explicit, inner_type, tagging_mode)
    choice_element = is_choice_type(registry, inner_type)
    set_element = is_set_type(registry, inner_type)

    return AsnField(
        field_name=field.field_name,
        field_type=inner_type,
        optional=field.optional,
        repeated=repeated,
        choice=choice_element,
        set_=set_element,
        tag_number=tag_number,
        tag_class=tag_class,
        explicit=effective_explicit(registry, explicit_tag, repeated, choice_element),
        universal_tag_override=resolve_universal_tag_override(registry, inner_type),
        children=children,
    )


def parse_field_lines(registry, raw_body, visiting, depth, cache, tagging_mode):
    parsed_leaves = []
    for line in split_field_entries(raw_body):
        trimmed = line.strip()
        if not trimmed:
            continue
        parsed = parse_field_line(trimmed, tagging_mode)
        if parsed is not None:
            parsed_leaves.append(parsed)

    auto_assign = tagging_mode == 'AUTOMATIC' and all(f.tag_number is None for f in parsed_leaves)

    fields = []
    for index, parsed in enumerate(parsed_leaves):
        if auto_assign:
            parsed.tag_number = index
            parsed.tag_class = 'CONTEXT'
            parsed.explicit = False

        inner_type = strip_constraint(parsed.field_type)
        repeated = parsed.repeated or is_alias_repeated(registry, inner_type)
        children = resolve_by_type_name(registry, inner_type, visiting, depth + 1, cache, tagging_mode)

        choice_element = is_choice_type(registry, inner_type)
        set_element = is_set_type(registry, inner_type)
        fields.append(AsnField(
            field_name=parsed.field_name,
            field_type=inner_type,
            optional=parsed.optional,
            repeated=repeated,
            choice=choice_element,
            set_=set_element,
            tag_number=parsed.tag_number,
            tag_class=parsed.tag_class,
            explicit=effective_explicit(registry, parsed.explicit, repeated, choice_element),
            universal_tag_override=resolve_universal_tag_override(registry, inner_type),
            children=children,
        ))
    return fields


def resolve_root(registry: dict, root_type_name: str, tagging_mode: str):
    """Returns (root_kind, fields)."""
    resolved_name = root_type_name
    current = registry.get(resolved_name)
    guard = set()
    while current is not None and current.kind == 'ALIAS' and resolved_name not in guard:
        guard.add(resolved_name)
        target = normalize_alias_target(current.alias_target)
        target = extract_repeated_inner_type(target) if is_repeated_expression(target) else target
        resolved_name = target
        current = registry.get(target)
    if current is None:
        return None, []

    cache = {}
    if current.kind == 'CHOICE':
        alts = resolve_choice_alternative(registry, resolved_name, current.raw_body, set(), 0, cache, tagging_mode)
        return 'CHOICE', alts

    fields = resolve_by_type_name(registry, resolved_name, set(), 0, cache, tagging_mode)
    return current.kind, fields

# --------------------------------------------------------------------------
# Shape prediction (mirrors BerEncoderService.wrapInTlv / encodeRepeated,
# at the STRUCTURAL level only - no value encoding needed).
# --------------------------------------------------------------------------

def expected_leaf_tag(field: AsnField) -> int:
    """The UNIVERSAL tag number a primitive leaf must carry on the wire.

    Mirrors BerEncoderService.wrapLeafInUniversalTlv: an explicit
    [UNIVERSAL n] re-tag from the schema wins, otherwise the tag implied by the
    resolved primitive type applies.
    """
    if field.universal_tag_override is not None:
        return field.universal_tag_override
    upper = (field.field_type or '').upper().strip()
    if upper.startswith('INTEGER'):
        return 2
    if upper.startswith('ENUMERATED'):
        return 10
    if upper.startswith('BOOLEAN'):
        return 1
    if upper.startswith('OCTET STRING') or upper.startswith('OCTETSTRING'):
        return 4
    if upper.startswith('UTF8STRING'):
        return 12
    if upper.startswith('IA5STRING'):
        return 22
    return 4  # unknown textual/custom types fall back to OCTET STRING


def predict_shapes(field: AsnField, path, out: dict, max_depth=6):
    """Recursively walks the resolved field tree, recording an expected shape
    for every CONSTRUCTED field (has children or is repeated) keyed by its
    tag-number path from the record root. Leaf (non-constructed) fields are
    skipped - they carry no wrap-vs-no-wrap ambiguity."""
    if field.tag_number is None or len(path) >= max_depth:
        return
    my_path = path + (field.tag_number,)

    is_constructed = bool(field.children) or field.repeated
    if not is_constructed:
        return

    if field.repeated:
        if field.choice:
            shape = 'repeated_choice_direct'  # each element: own CHOICE-alt CONTEXT tag, no wrap
        elif not field.children:
            # SEQUENCE OF <primitive>, e.g. sDP-Session-Description [4] SEQUENCE OF
            # GraphicStringImp. The elements are PRIMITIVE leaves carrying their own
            # universal tag - NOT constructed SEQUENCEs. Expecting a UNIVERSAL
            # SEQUENCE per element here was a bug in this predictor: it flagged the
            # very same 5 paths in our output AND in both EMM-accepted reference
            # captures, which is the signature of a wrong expectation rather than a
            # real encoding fault.
            shape = f'repeated_elem_primitive_{expected_leaf_tag(field)}'
            out[my_path] = shape
            return
        else:
            shape = 'repeated_elem_set' if field.set_ else 'repeated_elem_seq'
        shape += '_explicit' if field.explicit else '_implicit'
    else:
        if field.choice:
            shape = 'scalar_choice_direct'  # always direct regardless of explicit (X.680 8.3)
        else:
            shape = ('scalar_set_explicit' if field.explicit else 'scalar_set_implicit') if field.set_ \
                else ('scalar_seq_explicit' if field.explicit else 'scalar_seq_implicit')

    out[my_path] = shape

    # Recurse into children using the SAME path prefix (children's own tags
    # are relative to this field, one level deeper) - for repeated fields,
    # every element shares the same child-field schema (one resolved shape,
    # applied per element), so we still recurse using field.children once.
    for child in field.children:
        predict_shapes(child, my_path, out, max_depth)


def build_expected_shapes(contents: str, root_type_name: str, max_depth=6) -> dict:
    registry = build_registry(contents)
    tagging_mode = detect_tagging_mode(contents)
    kind, fields = resolve_root(registry, root_type_name, tagging_mode)
    out = {}
    for f in fields:
        predict_shapes(f, (), out, max_depth)
    return out, kind, fields
