use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::{Mutex, OnceLock};
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Clone, Serialize)]
struct Channel {
    id: usize,
    name: String,
    url: String,
    group: String,
    favorite: bool,
}

#[derive(Default)]
struct Engine {
    channels: Vec<Channel>,
    favorites: HashSet<String>,
    hidden: HashSet<String>,
}

static ENGINE: OnceLock<Mutex<Engine>> = OnceLock::new();
fn engine() -> &'static Mutex<Engine> {
    ENGINE.get_or_init(|| Mutex::new(Engine::default()))
}
fn with_engine<T>(f: impl FnOnce(&mut Engine) -> T) -> T {
    let mut guard = engine().lock().unwrap_or_else(|poison| poison.into_inner());
    f(&mut guard)
}

fn attribute(line: &str, key: &str) -> Option<String> {
    let needle = format!("{key}=\"");
    let start = line.find(&needle)? + needle.len();
    Some(line[start..].split('"').next()?.to_string())
}
fn title_start(line: &str) -> Option<usize> {
    let mut quoted = false;
    for (i, c) in line.char_indices() {
        if c == '"' { quoted = !quoted; }
        if c == ',' && !quoted { return Some(i + 1); }
    }
    None
}
fn parse(input: &str, favorites: &HashSet<String>) -> Vec<Channel> {
    let mut result = Vec::new();
    let (mut name, mut group) = (String::new(), String::new());
    for raw in input.lines() {
        let line = raw.trim();
        if line.starts_with("#EXTINF:") {
            name = title_start(line).map(|n| line[n..].trim().to_string()).unwrap_or_default();
            if name.is_empty() {
                name = attribute(line, "tvg-name").unwrap_or_default();
            }
            group = attribute(line, "group-title").unwrap_or_default();
        } else if line.starts_with("#EXTGRP:") {
            group = line[8..].trim().to_string();
        } else if line.starts_with("https://") {
            let url = line.to_string();
            let label = if name.is_empty() { format!("Channel {}", result.len() + 1) } else { name.clone() };
            result.push(Channel {
                id: result.len(),
                name: label,
                favorite: favorites.contains(&url),
                url,
                group: if group.is_empty() { "Other".into() } else { group.clone() },
            });
            name.clear();
            group.clear();
        }
    }
    result
}
fn matches(c: &Channel, query: &str, group: &str, favorites_only: bool, hidden: &HashSet<String>) -> bool {
    !hidden.contains(&c.url)
        && (!favorites_only || c.favorite)
        && (group.is_empty() || group == "All" || c.group.eq_ignore_ascii_case(group))
        && (query.is_empty()
            || c.name.to_lowercase().contains(&query.to_lowercase())
            || c.group.to_lowercase().contains(&query.to_lowercase()))
}
fn lineup<'a>(e: &'a Engine, query: &str, group: &str, favorites_only: bool) -> Vec<&'a Channel> {
    e.channels.iter().filter(|c| matches(c, query, group, favorites_only, &e.hidden)).collect()
}
fn as_json<T: Serialize>(value: &T) -> String {
    serde_json::to_string(value).unwrap_or_else(|_| "null".to_string())
}
fn from_java(env: &mut JNIEnv, s: JString) -> String {
    env.get_string(&s).map(|v| v.into()).unwrap_or_default()
}
fn to_java(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_load(
    mut env: JNIEnv, _: JClass, input: JString,
) -> jint {
    let input = from_java(&mut env, input);
    with_engine(|e| {
        e.channels = parse(&input, &e.favorites);
        e.channels.len().min(i32::MAX as usize) as jint
    })
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_visible(
    mut env: JNIEnv, _: JClass, query: JString, group: JString, favorites_only: jboolean,
) -> jstring {
    let q = from_java(&mut env, query);
    let g = from_java(&mut env, group);
    let json = with_engine(|e| as_json(&lineup(e, &q, &g, favorites_only != 0)));
    to_java(&mut env, json)
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_navigate(
    mut env: JNIEnv, _: JClass, current: jint, direction: jint, query: JString,
    group: JString, favorites_only: jboolean,
) -> jstring {
    let q = from_java(&mut env, query);
    let g = from_java(&mut env, group);
    let json = with_engine(|e| {
        let list = lineup(e, &q, &g, favorites_only != 0);
        if list.is_empty() { return "null".to_string(); }
        let index = list.iter().position(|c| c.id == current as usize);
        let next = match index {
            Some(i) if direction < 0 => (i + list.len() - 1) % list.len(),
            Some(i) => (i + 1) % list.len(),
            None => 0,
        };
        as_json(list[next])
    });
    to_java(&mut env, json)
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_random(
    mut env: JNIEnv, _: JClass, current: jint, query: JString, group: JString,
    favorites_only: jboolean,
) -> jstring {
    let q = from_java(&mut env, query);
    let g = from_java(&mut env, group);
    let json = with_engine(|e| {
        let list = lineup(e, &q, &g, favorites_only != 0);
        if list.is_empty() { return "null".to_string(); }
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_nanos();
        let mut i = (nanos % list.len() as u128) as usize;
        if list.len() > 1 && list[i].id == current as usize { i = (i + 1) % list.len(); }
        as_json(list[i])
    });
    to_java(&mut env, json)
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_toggleFavorite(
    mut env: JNIEnv, _: JClass, id: jint,
) -> jboolean {
    with_engine(|e| {
        let Some(channel) = e.channels.get_mut(id as usize) else { return 0; };
        channel.favorite = !channel.favorite;
        let (url, favorite) = (channel.url.clone(), channel.favorite);
        for c in &mut e.channels {
            if c.url == url { c.favorite = favorite; }
        }
        if favorite { e.favorites.insert(url); } else { e.favorites.remove(&url); }
        favorite as jboolean
    })
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_favorites(
    mut env: JNIEnv, _: JClass,
) -> jstring {
    to_java(&mut env, with_engine(|e| as_json(&e.favorites)))
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_restoreFavorites(
    mut env: JNIEnv, _: JClass, json: JString,
) {
    let raw = from_java(&mut env, json);
    with_engine(|e| {
        e.favorites = serde_json::from_str::<HashSet<String>>(&raw).unwrap_or_default();
        for c in &mut e.channels { c.favorite = e.favorites.contains(&c.url); }
    });
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_categories(
    mut env: JNIEnv, _: JClass,
) -> jstring {
    let json = with_engine(|e| {
        let mut groups: Vec<_> = e.channels.iter().map(|c| c.group.clone()).collect();
        groups.sort_unstable_by_key(|v| v.to_lowercase());
        groups.dedup_by(|a, b| a.eq_ignore_ascii_case(b));
        groups.insert(0, "All".to_string());
        as_json(&groups)
    });
    to_java(&mut env, json)
}

#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_hide(
    _: JNIEnv, _: JClass, id: jint,
) {
    with_engine(|e| {
        if let Some(c) = e.channels.get(id as usize) { e.hidden.insert(c.url.clone()); }
    });
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_hidden(
    mut env: JNIEnv, _: JClass,
) -> jstring {
    to_java(&mut env, with_engine(|e| as_json(&e.hidden)))
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_restoreHidden(
    mut env: JNIEnv, _: JClass, json: JString,
) {
    let raw = from_java(&mut env, json);
    with_engine(|e| e.hidden = serde_json::from_str(&raw).unwrap_or_default());
}
#[no_mangle]
pub extern "system" fn Java_org_channelsurfer_app_NativeEngine_exportVisible(
    mut env: JNIEnv, _: JClass, query: JString, group: JString, favorites_only: jboolean,
) -> jstring {
    let q = from_java(&mut env, query);
    let g = from_java(&mut env, group);
    let output = with_engine(|e| {
        let mut out = String::from("#EXTM3U\n");
        for c in lineup(e, &q, &g, favorites_only != 0) {
            // Keep titles as display text; strip M3U delimiters.
            let name = c.name.replace('\n', " ").replace('\r', " ").replace(',', " ");
            let group = c.group.replace('\n', " ").replace('\r', " ").replace('"', " ");
            out.push_str(&format!("#EXTINF:-1 group-title=\"{}\",{}\n{}\n", group, name, c.url));
        }
        out
    });
    to_java(&mut env, output)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn parses_extended_m3u_and_group() {
        let data = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Backup\" group-title=\"News, Local\",Station One\nhttps://example.org/one.m3u8\n#EXTINF:-1,Two\n#EXTGRP:Movies\nhttps://example.org/two\n";
        let list = parse(data, &HashSet::new());
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].name, "Station One");
        assert_eq!(list[0].group, "News, Local");
        assert_eq!(list[1].group, "Movies");
        assert!(matches(&list[0], "station", "All", false, &HashSet::new()));
    }
}
