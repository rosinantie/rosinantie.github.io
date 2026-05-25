---
layout: post
title: "My dotfiles — what's in ~/.dotfiles and why"
date: 2026-05-25
categories: tech
---

I keep everything that customises my machine inside a single folder: `~/.dotfiles`. Nothing in there is exotic, but every file is the result of "I got annoyed once and fixed it." This post is mostly a tour, written so future-me remembers *why* the settings are what they are.

```
~/.dotfiles
├── .gitconfig
├── .gitignore
├── .ssh
├── .tmux.conf
├── zshrc
├── vimrc/
│   ├── idea/        # IdeaVim config
│   └── nvim/        # Neovim config (symlinked into ~/.config/nvim)
└── .config/
    ├── alacritty/
    ├── wezterm/
    ├── karabiner/
    ├── coc/
    └── Code/
```

Two important details about the layout:

1. The folder is a **git repo** (note the `.git` and `.gitignore`). That's the whole point — I can clone it on a new machine and symlink everything into `$HOME`.
2. `.config/nvim` is a **symlink** back to `vimrc/nvim`. That way I can keep all my editor configs together under `vimrc/` (alongside the IntelliJ IdeaVim one) and still let Neovim find it at the path it expects.

---

## 1. `.gitconfig` — switching identity in one alias

I have two git identities: a personal one and a work one. Forgetting to switch is how you end up committing personal-email commits inside a company repo. So:

```ini
[alias]
    ci-personal = "!f() { git config user.name 'my-personal-handle'; \
                          git config user.email 'personal@example.com'; }; f"
    ci-office   = "!f() { git config user.name 'my-work-handle'; \
                          git config user.email 'work@example.com'; }; f"
    whoami      = git whoami config user.name && git config user.email

[init]
    defaultBranch = main
```

First thing I do inside a freshly cloned repo: `git ci-personal` or `git ci-office`. Then `git whoami` to confirm. It writes to that repo's local `.git/config`, not the global one — so each repo remembers who I am.

`defaultBranch = main` just stops `git init` from creating a `master` branch I'll have to rename ten seconds later.

---

## 2. `.tmux.conf` — the few things that make tmux feel like home

The full file is ~50 lines. The decisions that actually matter:

```tmux
unbind C-b
set-option -g prefix C-a
bind-key C-a send-prefix
```

I moved the prefix from `C-b` to `C-a` because `C-b` collides with "page up" in less/vim/man-pages. `C-a` only collides with "go to start of line" in shell — and I rarely need that inside a tmux pane.

```tmux
set -g base-index 1
set -g pane-base-index 1
```

Windows and panes count from `1` instead of `0`. The prefix-then-number motion (`C-a 1`, `C-a 2`) maps directly to the number row of the keyboard, where `1` is the first key. Starting at `0` means the first window is on the *second* key, which my hand never learns.

```tmux
bind '"' split-window -v -c "#{pane_current_path}"
bind % split-window -h -c "#{pane_current_path}"
bind c new-window -c "#{pane_current_path}"
```

By default, tmux splits start in your home directory. That's almost never what I want — I'm splitting because I want to run a second command *here*. `#{pane_current_path}` makes the new pane inherit the current one's directory.

```tmux
setw -g mode-keys vi
bind-key -T copy-mode-vi y send-keys -X copy-pipe-and-cancel "pbcopy"
bind-key -T copy-mode-vi MouseDragEnd1Pane send-keys -X copy-pipe-and-cancel "pbcopy"
```

This is the one I'd miss most if it disappeared. Press prefix + `[` to enter copy mode, navigate with `hjkl`, `v` to start selection, `y` to copy — and the selection lands in the macOS system clipboard via `pbcopy`. No `Cmd-C`, no losing my mouse position, no terminal weirdness with multi-pane copy.

```tmux
bind h select-pane -L
bind j select-pane -D
bind k select-pane -U
bind l select-pane -R
bind -r H resize-pane -L 5
bind -r J resize-pane -D 5
bind -r K resize-pane -U 5
bind -r L resize-pane -R 5
```

Pane navigation in vim direction (lowercase), pane resizing in shifted vim direction (uppercase). The `-r` on the resize bindings means "repeatable" — I can press prefix once, then mash `H` four times in a row, instead of pressing the prefix between each.

---

## 3. `zshrc` — the boring half: shell + language toolchain

The file is split into roughly four parts: PATHs and language managers, oh-my-zsh setup, custom functions, and aliases. Here are the parts that actually do work.

**Java toggling.** I use both Java 17 and 21 depending on which project I'm in. The simplest version of "version manager" I could justify:

```zsh
# Java 17
export JAVA_HOME="/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"

# Java 21
# export JAVA_HOME="/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
# export PATH="$JAVA_HOME/bin:$PATH"
```

Comment one out, uncomment the other, source the file. I tried `jenv` for a while and got tired of fighting it. For a two-version setup, two lines and a comment beat a tool.

**Other runtimes.** NVM for Node, pyenv for Python, rbenv for Ruby. The pattern is the same every time:

```zsh
export PYENV_ROOT="$HOME/.pyenv"
export PATH="$PYENV_ROOT/bin:$PATH"
eval "$(pyenv init -)"
```

The `eval` is the magic step — it injects a shim into the shell so that calling `python` actually goes through the version-manager's lookup, not straight to `/usr/bin/python`.

**Oh My Zsh.** Theme `robbyrussell` (the default, the one with the arrow prompt that turns red on a failed command), `git` plugin for completions, vi keybindings via `bindkey -v`. That's it. I keep being tempted by powerlevel10k and keep deciding I don't actually need it.

**Git aliases.** These get used hundreds of times a day:

```zsh
alias ga='git add .'
alias gr='git restore --staged .'
alias gc='git commit -m'
alias gp='git push'
alias gl='git pull'
alias gs='git status'
alias glog='git log --oneline --graph --all --decorate'
alias gco='git checkout'
alias gcb='git checkout -b'
alias glc='git log -1 --stat'
```

The one I want to call out is `glog` — `--oneline --graph --all --decorate` is the four-flag combo that turns `git log` into the branch-diagram view that every git GUI tries to replicate. Once you've typed `glog` once, you stop needing a GUI to "see the shape" of the repo.

---

## 4. `zshrc` — the interesting half: deployment + tmux session helpers

The custom functions are where the file earns its keep.

**One-key deployment to a remote Tomcat.** I deploy WARs to three different servers. Each used to be: `cd` into the project, run `mvn clean package`, then `scp` the WAR to the right path. Four commands, easy to do in the wrong order. Now:

```zsh
upload_to_prod_a() {
    PROJECT_DIR="$HOME/Documents/Github/main-api"
    WAR_PATH="$PROJECT_DIR/target/main-api.war"

    echo "Building the project..."
    cd "$PROJECT_DIR" || { echo "Project directory not found"; return 1; }
    mvn clean package || { echo "Build failed"; return 1; }

    echo "Deploying WAR to remote server..."
    scp "$WAR_PATH" deploy@prod-a:/opt/tomcat/webapps/ || \
        { echo "Deployment failed"; return 1; }

    echo "Deployment completed successfully!"
}
```

Two important things going on here:

1. Each step `|| { … return 1; }` — if the build fails, I never get to the `scp`. That's the whole reason this is a function and not three aliases.
2. The `scp` target is `prod-a`, not an IP. That short name resolves because of `~/.ssh/config`, which is the *other* file that makes this whole setup work. (I'll do a `.ssh/config` post separately — it's its own thing.)

There's also a `_with_backup` variant, which does the same dance but first SSHes into the box and copies the current WAR into a timestamped `webservices_backup/` folder. That came out of one production rollback I never want to repeat.

**`tmux-dev` — boot a whole workday.** This is my favourite function in the entire file. Running `tmux-dev` does this:

```
Window 0: nvim     (in ~/projects)
Window 1: workspace (empty shell in $HOME)
Window 2: alibaba-icm (split: tail catalina.out / shell on the server)
Window 3: hetzner     (split: tail catalina.out / shell on the server)
Window 4: eosium-cpp  (cd'd into my C++ side project)
Window 5: P-8080      (mvn spring-boot:run -Plocal in the local API)
```

It checks `tmux has-session -t dev-hub` first, so running it a second time just *attaches* to the existing session instead of rebuilding it. Cold start in the morning: open WezTerm, type `tmux-dev`, the whole workspace materialises — backend running, two server log tails ready, editor in the right folder. Closing the laptop lid doesn't kill any of it.

There are also `tmux-hetzner` and `tmux-alibaba-icm` for when I only need *one* of those server windows on its own.

---

## 5. The runaway PATH — a small horror story

Near the top of my `zshrc` I have, inexplicably:

```zsh
echo 'export PATH="/Applications/Postgres.app/Contents/Versions/latest/bin:$PATH"' >> ~/.zshrc
```

That line does **not** export the PATH. It *appends another export line to the file itself*. Every time I open a terminal, the file gets one line longer. I wrote it months ago when I was setting up Postgres.app and somehow combined "add this line to your shell config" with "I'll do it from inside the shell config." Predictable result: the file is now ~990 lines long and ~970 of those lines are the same `export PATH=…postgres…` line stacked on top of each other.

The shell doesn't actually care — re-exporting the same PATH is a no-op — so nothing visibly broke. I only noticed when I went to add a new alias and had to scroll past the wall of duplicates to find the right spot.

The fix is a one-liner (`export PATH="/Applications/Postgres.app/Contents/Versions/latest/bin:$PATH"` — no `echo`, no `>> ~/.zshrc`), and then delete the duplicates. I've left the file as-is in this post on purpose, because **the lesson is the interesting part**: any `>> ~/.zshrc` inside `~/.zshrc` is a footgun. Shell init files should *be* the configuration, not *modify* the configuration.

---

## 6. Two terminals: Alacritty for "just run it", WezTerm for everything else

I have configs for both, and I use both for different reasons.

**Alacritty (`alacritty.toml`)** — five lines, the whole file:

```toml
[font]
normal = { family = "JetBrainsMono Nerd Font", style = "Regular" }
size = 15.0

[window]
startup_mode = "Fullscreen"
```

Font, size, fullscreen on launch. That's it. Alacritty is what I use when I want a terminal *now* and I don't care about tabs, splits, or backdrops. It opens instantly, renders fast, and doesn't have opinions.

**WezTerm (`wezterm.lua`)** — multi-file Lua config split into modules:

```lua
local Config = require('config')

require('utils.backdrops')
   :set_focus('#000000')
   :set_images_dir(wezterm.home_dir .. '/.dotfiles/.config/wezterm/backdrops/')
   :set_images()
   :random()

require('events.left-status').setup()
require('events.right-status').setup({ date_format = '%a %H:%M:%S' })
require('events.tab-title').setup({ hide_active_tab_unseen = false })

return Config:init()
   :append(require('config.appearance'))
   :append(require('config.bindings'))
   :append(require('config.domains'))
   :append(require('config.fonts'))
   :append(require('config.general'))
   :append(require('config.launch')).options
```

WezTerm is what I use when I'm *living* in the terminal — the `tmux-dev` workday, long-running log tails, working on something for hours. It has tab titles, a clock in the corner, a random backdrop image, and SSH domains so I can open a new tab that's already SSH'd into a remote host. The Lua config is the heavyweight version; Alacritty's toml is the featherweight. Same machine, different jobs.

---

## 7. `karabiner/` — Caps Lock becomes a fifth modifier

macOS has four modifiers: ⌃ ⌥ ⇧ ⌘. Almost every useful combination is already taken by some app. So I added a fifth: pressing **Caps Lock** sends `⌃⌥⇧⌘` simultaneously (the "Hyper" key). Nothing on macOS uses all four modifiers together, which makes it a clean shortcut namespace I own.

```ts
{
  description: "Caps Lock -> Hyper Key",
  from: { key_code: "caps_lock", modifiers: { optional: ["any"] } },
  to: [{ set_variable: { name: "hyper", value: 1 } }],
  to_after_key_up: [{ set_variable: { name: "hyper", value: 0 } }],
  to_if_alone: [{ key_code: "escape" }],
  type: "basic",
}
```

The `to_if_alone: escape` is the clever bit. If I press Caps Lock *and release it without pressing anything else*, it acts as Escape. Useful in vim. If I hold it while pressing another key, it's Hyper. One key, two jobs, no conflict.

With Hyper set up, I have sub-layers:

- `Hyper + o + i` → open IntelliJ
- `Hyper + o + s` → open Slack
- `Hyper + o + t` → open Terminal
- `Hyper + w + l` → snap current window to right-half
- `Hyper + w + f` → maximize window
- `Hyper + s + u` / `s + j` → volume up / down

The config is a TypeScript file (`rules.ts`) that compiles down to the JSON Karabiner-Elements actually reads. The TS side is much easier to maintain — I get types, autocomplete, and helpers like `app("Slack")` instead of hand-writing JSON. The original rules layout is adapted from a public config (Max Stoiber's, originally) with the app list rewritten for what I actually use.

---

## 8. Neovim — minimal init.vim, with one tmux trick

`~/.config/nvim/init.vim` is symlinked to `~/.dotfiles/vimrc/nvim/init.vim`. The full config is ~160 lines. Highlights:

```vim
set number
set relativenumber
set clipboard=unnamedplus
set scrolloff=4

set tabstop=2
set shiftwidth=2
set softtabstop=2
set expandtab

set wrap
set linebreak
set breakindent
set showbreak=↳\
```

Relative numbers for fast `5j`/`10k` jumps, system clipboard integration, 2-space indent (I do a lot of YAML and JS), and **soft-wrap with a visible continuation marker** (`↳`) so I can see when a "line" is actually one logical line that's wrapping.

```vim
nnoremap j gj
nnoremap k gk
```

When a line is soft-wrapped, plain `j` jumps *over* the wrapped portion to the next real line. `gj` moves down by visual row, which is what I actually want 99% of the time.

**Plugins**, via vim-plug:

```vim
Plug 'nanotech/jellybeans.vim'         " colorscheme
Plug 'windwp/nvim-autopairs'           " auto-close brackets
Plug 'neoclide/coc.nvim',  {'branch': 'release'}  " LSP/intellisense
Plug 'vim-airline/vim-airline'         " statusline
Plug 'akinsho/bufferline.nvim'         " tabs for buffers
Plug 'junegunn/fzf'                    " fuzzy file finder
Plug 'preservim/nerdtree'              " file tree sidebar
Plug 'mfussenegger/nvim-dap'           " debug adapter protocol
Plug 'rcarriga/nvim-dap-ui'            " debugger UI
```

Coc.nvim is the heavyweight here — it gives me LSP-style completion, jump-to-definition, format-on-save for C/C++:

```vim
autocmd BufWritePre *.c,*.cpp,*.h,*.hpp silent! call CocAction('format')
```

**The F5-runs-this-file trick.** This is the part I'm proudest of, and it's barely any code:

```vim
augroup RunFile
  autocmd!
  autocmd FileType python nnoremap <buffer> <F5> :w<CR>:silent
    \ ![ $(tmux list-panes \| wc -l) -eq 1 ] && tmux split-window -h -p 40<CR>
    \ :silent !tmux send-keys -t right "clear; python3 %" C-m<CR>
    \ :silent !tmux select-pane -t left<CR>:redraw!<CR>

  autocmd FileType cpp nnoremap <buffer> <F5> :w<CR>:silent
    \ ![ $(tmux list-panes \| wc -l) -eq 1 ] && tmux split-window -h -p 40<CR>
    \ :silent !tmux send-keys -t right "clear; cmake -S . -B build && cmake --build build && ./build/eosium" C-m<CR>
    \ :silent !tmux select-pane -t left<CR>:redraw!<CR>
augroup END
```

What this does, read top-to-bottom:

1. Save the file (`:w`).
2. If there's only one tmux pane in the current window, split off a right-hand pane that takes 40% of the width.
3. Send a command into the right pane: `clear; python3 %` (where `%` is the current file). For C++, it's a full CMake build + run instead.
4. Move focus back to the left pane (where vim still is).

Net result: press F5 inside any Python or C++ file, and a side-pane spins up running that file. Edit, F5, see output, edit, F5 — all without leaving vim or touching the mouse. This only works because tmux is *already* the window manager for the terminal — vim doesn't need its own terminal emulator, it just borrows tmux's.

---

## What's not here, on purpose

- **No fish, no powerlevel10k, no starship.** I tried each. They're nice. They also add a setup step on every new machine and a re-learning step every six months. zsh + oh-my-zsh + `robbyrussell` does 90% of what I need, and I can sit down at a fresh `bash` shell and still function.
- **No tmuxinator / smug.** My `tmux-dev` function is ~40 lines of zsh and does exactly what I want. A YAML-config-driven tool would have to be learned, configured, kept up-to-date, and remembered to install on every machine.
- **No GUI git client.** `gs`, `glog`, `gco`, and `gcb` cover everything I do day-to-day. The one time I want a visual diff, I open IntelliJ.

The dotfiles answer the question "what is the smallest amount of customisation that turns a fresh Mac into a workstation I can actually work on?" Everything in `~/.dotfiles` exists because, at some point, I noticed I was about to do something annoying for the second time.
