package com.sailens.shell.di

import org.koin.core.module.Module
import org.koin.dsl.module

/** Release variant: no debug-only bindings. */
val shellDebugModule: Module = module { }
