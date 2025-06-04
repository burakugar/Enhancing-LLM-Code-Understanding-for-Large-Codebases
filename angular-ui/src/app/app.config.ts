import {provideHttpClient, withInterceptorsFromDi} from '@angular/common/http';
import {ApplicationConfig, provideZoneChangeDetection} from '@angular/core';
import {provideRouter} from '@angular/router';
import {provideHighlightOptions} from 'ngx-highlightjs';
import {provideMarkdown} from 'ngx-markdown'; // <--- IMPORT THIS
import {provideAnimationsAsync} from '@angular/platform-browser/animations/async';
import {routes} from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({eventCoalescing: true}),
    provideRouter(routes),
    provideAnimationsAsync(),
    provideHttpClient(withInterceptorsFromDi()),
    provideHighlightOptions({
      fullLibraryLoader: () => import('highlight.js'),
    }),
    provideMarkdown(),
  ]
};
