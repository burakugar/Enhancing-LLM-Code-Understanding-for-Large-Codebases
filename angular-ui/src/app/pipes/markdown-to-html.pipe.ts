import {Pipe, PipeTransform} from '@angular/core';
import {DomSanitizer, SafeHtml} from '@angular/platform-browser';
import {marked} from 'marked';

@Pipe({
  name: 'markdownToHtml',
  standalone: true
})
export class MarkdownToHtmlPipe implements PipeTransform {

  constructor(private sanitizer: DomSanitizer) {
    marked.setOptions({
      gfm: true,
      breaks: true,
      silent: false,
      async: false
    });
  }

  transform(value: string | null | undefined): SafeHtml {
    if (value === null || value === undefined || value.trim() === '') {
      return '';
    }

    const html = marked.parse(value) as string;

    return this.sanitizer.bypassSecurityTrustHtml(html);
  }
}
